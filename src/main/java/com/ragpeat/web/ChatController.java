package com.ragpeat.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.chat.ChatListener;
import com.ragpeat.chat.ChatService;
import com.ragpeat.chat.ConversationRepository;
import com.ragpeat.chat.ConversationRepository.AnswerMetrics;
import com.ragpeat.chat.Turn;
import com.ragpeat.search.SearchResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * La API que usa la página: POST /api/chat con {"question": "..."}.
 *
 * Responde con streaming (SSE, Server-Sent Events): la conexión HTTP se queda abierta y el servidor
 * va mandando eventos según los tiene:
 *   event: delta    data: {"text": "..."}             un trozo de texto de la respuesta
 *   event: discard  data: {}                          lo enviado hasta ahora no era la respuesta: bórralo
 *   event: done     data: {"answer": ..., "sources": [...]}   la respuesta final, con sus fuentes
 *   event: error    data: {"error": "..."}            algo falló a mitad
 *
 * Si la pregunta no es válida o se pasa del límite, responde como antes: un JSON con 400 o 429. Si ya hay demasiadas
 * respuestas generándose a la vez, 503.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final int MAX_QUESTION_LENGTH = 3000;   // da para pegar una analítica (PersonalData quita los datos personales al guardar)
    private static final long STREAM_TIMEOUT_MS = 120_000;

    /**
     * Cuántos mensajes anteriores ve Claude: 20 = las 10 últimas preguntas con sus respuestas (las antiguas, recortadas:
     * ver ChatService.compact). Con 3, alguien que contaba su situación al principio la "olvidaba" a la cuarta pregunta.
     */
    private static final int HISTORY_MESSAGES = 20;

    private final ChatService chatService;
    private final ConversationRepository conversations;
    private final RateLimiter rateLimiter;
    private final ClientInfo clientInfo;

    /**
     * Cuántas respuestas se generan a la vez como mucho. Los límites por IP y por día cuentan preguntas, no cuántas
     * van en paralelo: sin esto, muchas IPs a la vez podrían abrir cientos de llamadas a Claude y a Voyage de golpe.
     */
    private final Semaphore generating;

    /**
     * La respuesta se genera en OTRO hilo: así el hilo que atiende la petición queda libre enseguida
     * y la conexión sigue abierta mientras Claude escribe. Usamos hilos virtuales (Java 21): son tan
     * ligeros que podemos tener miles a la vez esperando a Claude sin agotar el servidor.
     */
    private final ThreadFactory workers = Thread.ofVirtual().name("chat-", 0).factory();

    public ChatController(ChatService chatService, ConversationRepository conversations, RateLimiter rateLimiter,
                          ClientInfo clientInfo, @Value("${chat.limit.concurrent:20}") int maxConcurrent) {
        this.chatService = chatService;
        this.conversations = conversations;
        this.rateLimiter = rateLimiter;
        this.clientInfo = clientInfo;
        this.generating = new Semaphore(maxConcurrent);
    }

    // Lo que llega y lo que sale, como records: Spring los convierte de/a JSON automáticamente.
    /** conversationId: null en la primera pregunta; después, el que devolvimos en la respuesta anterior. */
    /** internal: true desde un navegador nuestro (marcado con ?interno); sus conversaciones no cuentan en las métricas. */
    /** lang: el idioma de la página desde la que se pregunta ("es" o "en"); cualquier otra cosa se ignora. */
    record ChatRequest(String question, UUID conversationId, Boolean internal, String lang) {}

    record Source(int number, String title, String url, String type, String text) {}

    record ChatResponse(String answer, List<Source> sources, UUID conversationId, long messageId) {}

    record ErrorResponse(String error) {}

    /** value: 1 = 👍, -1 = 👎, null = quitar el voto. reason: con 👎, qué ha fallado (opcional). */
    record FeedbackRequest(UUID conversationId, Long messageId, Integer value, String reason) {}

    static final int MAX_REASON_LENGTH = 500;

    @PostMapping("/api/feedback")
    public ResponseEntity<?> feedback(@RequestBody FeedbackRequest request) {
        if (request.conversationId() == null || request.messageId() == null
                || (request.value() != null && request.value() != 1 && request.value() != -1)
                || (request.reason() != null && request.reason().length() > MAX_REASON_LENGTH)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Voto no válido."));
        }
        // El motivo solo tiene sentido con 👎: con 👍 o al quitar el voto, se borra.
        String reason = Integer.valueOf(-1).equals(request.value()) ? request.reason() : null;
        boolean saved = conversations.saveFeedback(request.conversationId(), request.messageId(), request.value(), reason);
        // 204 No Content: todo bien y no hay nada que devolver. 404: ese mensaje no existe en esa conversación.
        return saved ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, HttpServletRequest http) {
        String question = request.question() == null ? "" : request.question().strip();

        // 400 Bad Request: la pregunta no es válida. Se comprueba ANTES de gastar nada.
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("La pregunta debe tener entre 1 y " + MAX_QUESTION_LENGTH + " caracteres."));
        }
        // 503: ya hay demasiadas respuestas en marcha. Va antes del límite por IP para no gastarle una pregunta.
        if (!generating.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30")
                    .body(new ErrorResponse("Hay muchas preguntas a la vez. Prueba de nuevo en unos segundos."));
        }
        boolean started = false;
        try {
            // 429 Too Many Requests: esta persona (o todo el mundo hoy) ha llegado al límite.
            if (!rateLimiter.tryAcquire(clientInfo.ip(http))) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(new ErrorResponse("Has hecho muchas preguntas seguidas. Prueba de nuevo dentro de un rato."));
            }

            // La huella se calcula aquí, en el hilo de la petición: el objeto "http" no se puede usar
            // desde otro hilo cuando la petición ya ha pasado a modo streaming.
            String visitor = clientInfo.visitorHash(http);
            SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
            boolean internal = Boolean.TRUE.equals(request.internal());
            String pageLang = "en".equals(request.lang()) || "es".equals(request.lang()) ? request.lang() : null;
            AtomicBoolean closed = new AtomicBoolean();
            Thread worker = workers.newThread(() -> {
                try {
                    respond(question, request.conversationId(), visitor, internal, pageLang, emitter, closed);
                } finally {
                    generating.release();
                }
            });
            // Si la conexión termina antes que la respuesta (la persona cierra la página, se agota el tiempo o falla
            // la red), se deja de generar: interrumpir el hilo corta la llamada a Claude o a Voyage que esté en curso.
            // Cuando la respuesta ya ha terminado, interrumpir un hilo acabado no hace nada.
            Runnable cancel = () -> {
                closed.set(true);
                worker.interrupt();
            };
            emitter.onTimeout(cancel);
            emitter.onError(e -> cancel.run());
            emitter.onCompletion(cancel);
            worker.start();
            started = true;
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
        } finally {
            if (!started) {
                generating.release();
            }
        }
    }

    /**
     * Se ejecuta en un hilo virtual: genera la respuesta y la va mandando por el emitter.
     * @param closed true cuando la conexión ya terminó y chat() ha interrumpido este hilo.
     */
    private void respond(String question, UUID requestedId, String visitor, boolean internal, String pageLang,
                         SseEmitter emitter, AtomicBoolean closed) {
        long start = System.currentTimeMillis();
        try {
            // Si nos mandan una conversación que existe, seguimos en ella; si no (primera pregunta,
            // o un id inventado o borrado), empezamos una nueva. Nunca nos fiamos del historial que
            // pudiera mandar la página: lo leemos de nuestra base de datos.
            UUID conversationId = requestedId != null && conversations.exists(requestedId)
                    ? requestedId
                    : conversations.create(visitor, internal);
            List<Turn> history = conversations.lastTurns(conversationId, HISTORY_MESSAGES);
            String document = conversations.documentText(conversationId);   // null si no adjuntó nada

            // Los fragmentos que citó su respuesta anterior: se los volvemos a enseñar para que pueda comprobarla
            // si la persona se la discute (ver ChatService.answer).
            SortedMap<Integer, SearchResult> previousSources =
                    chatService.sourcesOf(conversations.lastAnswerCited(conversationId));

            ChatAnswer answer = chatService.answer(history, previousSources, question, document, pageLang, new ChatListener() {
                @Override
                public void onText(String delta) {
                    send(emitter, "delta", Map.of("text", delta));
                }

                @Override
                public void onDiscardText() {
                    send(emitter, "discard", Map.of());
                }
            });
            List<Source> sources = answer.citedSources().entrySet().stream()
                    // El texto de los libros nunca sale del servidor (derechos de autor): solo título, página y enlace.
                    .map(e -> new Source(e.getKey(), e.getValue().title(), e.getValue().url(),
                            e.getValue().sourceType(), e.getValue().isBook() ? null : e.getValue().content()))
                    .toList();
            // Solo guardamos el intercambio si ha salido bien.
            long messageId = conversations.saveExchange(conversationId, question, answer.text(),
                    new AnswerMetrics(System.currentTimeMillis() - start,
                            answer.inputTokens(), answer.outputTokens(), answer.searches()),
                    answer.citedSources().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().chunkId())));
            send(emitter, "done", new ChatResponse(answer.text(), sources, conversationId, messageId));
            emitter.complete();
        } catch (ClientGone e) {
            log.debug("El cliente se desconectó: se deja de generar la respuesta");
        } catch (Exception e) {
            if (closed.get()) {
                // chat() interrumpió el hilo porque la conexión ya terminó: no es un fallo y no queda nadie a quien avisar.
                log.debug("Respuesta cancelada: la conexión terminó antes");
                return;
            }
            // Falló algo nuestro o de un servicio externo (Claude, Voyage, la base de datos).
            // Al usuario no le enseñamos detalles internos; los dejamos en el log para nosotros.
            log.error("Error respondiendo a la pregunta", e);
            try {
                send(emitter, "error", new ErrorResponse("No he podido responder ahora mismo. Inténtalo de nuevo en unos minutos."));
                emitter.complete();
            } catch (ClientGone gone) {
                log.debug("No se pudo avisar del error: el cliente se desconectó");
            }
        }
    }

    /** La conexión con el navegador ya no existe: no tiene sentido seguir pagando por generar la respuesta. */
    private static final class ClientGone extends RuntimeException {
        ClientGone(Exception cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }

    /**
     * Manda un evento. Si el usuario ha cerrado la página o se agotó el tiempo, el envío falla: no es un error
     * nuestro, pero sí la señal para dejar de generar (ClientGone corta el bucle de Claude en ChatService).
     */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGone(e);
        }
    }
}
