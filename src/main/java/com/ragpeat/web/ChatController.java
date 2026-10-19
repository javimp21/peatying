package com.ragpeat.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.chat.ChatListener;
import com.ragpeat.chat.ChatService;
import com.ragpeat.chat.ConversationRepository;
import com.ragpeat.chat.Turn;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Si la pregunta no es válida o se pasa del límite, responde como antes: un JSON con 400 o 429.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final int MAX_QUESTION_LENGTH = 500;
    private static final long STREAM_TIMEOUT_MS = 120_000;

    /** Cuántos mensajes anteriores ve Claude: 6 = las 3 últimas preguntas con sus respuestas. */
    private static final int HISTORY_MESSAGES = 6;

    private final ChatService chatService;
    private final ConversationRepository conversations;
    private final RateLimiter rateLimiter;
    private final boolean trustProxy;

    /**
     * La respuesta se genera en OTRO hilo: así el hilo que atiende la petición queda libre enseguida
     * y la conexión sigue abierta mientras Claude escribe. Usamos hilos virtuales (Java 21): son tan
     * ligeros que podemos tener miles a la vez esperando a Claude sin agotar el servidor.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService chatService, ConversationRepository conversations, RateLimiter rateLimiter,
                          @Value("${chat.trust-proxy:false}") boolean trustProxy) {
        this.chatService = chatService;
        this.conversations = conversations;
        this.rateLimiter = rateLimiter;
        this.trustProxy = trustProxy;
    }

    // Lo que llega y lo que sale, como records: Spring los convierte de/a JSON automáticamente.
    /** conversationId: null en la primera pregunta; después, el que devolvimos en la respuesta anterior. */
    record ChatRequest(String question, UUID conversationId) {}

    record Source(int number, String title, String url, String text) {}

    record ChatResponse(String answer, List<Source> sources, UUID conversationId) {}

    record ErrorResponse(String error) {}

    @PostMapping("/api/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, HttpServletRequest http) {
        String question = request.question() == null ? "" : request.question().strip();

        // 400 Bad Request: la pregunta no es válida. Se comprueba ANTES de gastar nada.
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("La pregunta debe tener entre 1 y " + MAX_QUESTION_LENGTH + " caracteres."));
        }
        // 429 Too Many Requests: esta persona (o todo el mundo hoy) ha llegado al límite.
        if (!rateLimiter.tryAcquire(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Has hecho muchas preguntas seguidas. Prueba de nuevo dentro de un rato."));
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        executor.execute(() -> respond(question, request.conversationId(), emitter));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** Se ejecuta en un hilo virtual: genera la respuesta y la va mandando por el emitter. */
    private void respond(String question, UUID requestedId, SseEmitter emitter) {
        try {
            // Si nos mandan una conversación que existe, seguimos en ella; si no (primera pregunta,
            // o un id inventado o borrado), empezamos una nueva. Nunca nos fiamos del historial que
            // pudiera mandar la página: lo leemos de nuestra base de datos.
            UUID conversationId = requestedId != null && conversations.exists(requestedId)
                    ? requestedId
                    : conversations.create();
            List<Turn> history = conversations.lastTurns(conversationId, HISTORY_MESSAGES);

            ChatAnswer answer = chatService.answer(history, question, new ChatListener() {
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
                    .map(e -> new Source(e.getKey(), e.getValue().title(), e.getValue().url(), e.getValue().content()))
                    .toList();
            // Solo guardamos el intercambio si ha salido bien.
            conversations.saveExchange(conversationId, question, answer.text());
            send(emitter, "done", new ChatResponse(answer.text(), sources, conversationId));
            emitter.complete();
        } catch (Exception e) {
            // Falló algo nuestro o de un servicio externo (Claude, Voyage, la base de datos).
            // Al usuario no le enseñamos detalles internos; los dejamos en el log para nosotros.
            log.error("Error respondiendo a la pregunta", e);
            send(emitter, "error", new ErrorResponse("No he podido responder ahora mismo. Inténtalo de nuevo en unos minutos."));
            emitter.complete();
        }
    }

    /**
     * Manda un evento. Si el usuario ha cerrado la página, el envío falla: no es un error nuestro,
     * así que lo anotamos y seguimos. (La respuesta de Claude se termina de generar igualmente.)
     */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("No se pudo enviar el evento '{}': el cliente se desconectó", event);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown(); // al parar la app, no aceptar más tareas
    }

    /**
     * La IP de quien pregunta.
     *
     * Detrás de un balanceador (como tendremos en AWS), la conexión viene del balanceador y la IP real
     * llega en la cabecera X-Forwarded-For. Pero esa cabecera la puede escribir cualquiera: si nos
     * fiáramos siempre, un script pondría una IP distinta en cada petición y se saltaría el límite.
     * Por eso:
     *  - Solo la usamos si chat.trust-proxy=true (lo activaremos en AWS).
     *  - Tomamos la ÚLTIMA IP de la lista: es la que añade nuestro balanceador, la única fiable.
     */
    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (trustProxy && forwarded != null && !forwarded.isBlank()) {
            String[] ips = forwarded.split(",");
            return ips[ips.length - 1].strip();
        }
        return http.getRemoteAddr();
    }
}
