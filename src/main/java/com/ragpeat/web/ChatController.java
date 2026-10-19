package com.ragpeat.web;

import java.util.List;

import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.chat.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * La API que usa la página: POST /api/chat con {"question": "..."}.
 * Devuelve la respuesta y los fragmentos citados para poder mostrarlos al pulsar [n].
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    static final int MAX_QUESTION_LENGTH = 500;

    private final ChatService chatService;
    private final RateLimiter rateLimiter;
    private final boolean trustProxy;

    public ChatController(ChatService chatService, RateLimiter rateLimiter,
                          @Value("${chat.trust-proxy:false}") boolean trustProxy) {
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
        this.trustProxy = trustProxy;
    }

    // Lo que llega y lo que sale, como records: Spring los convierte de/a JSON automáticamente.
    record ChatRequest(String question) {}

    record Source(int number, String title, String url, String text) {}

    record ChatResponse(String answer, List<Source> sources) {}

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

        try {
            ChatAnswer answer = chatService.answer(question);
            List<Source> sources = answer.citedSources().entrySet().stream()
                    .map(e -> new Source(e.getKey(), e.getValue().title(), e.getValue().url(), e.getValue().content()))
                    .toList();
            return ResponseEntity.ok(new ChatResponse(answer.text(), sources));
        } catch (Exception e) {
            // 503: falló algo nuestro o de un servicio externo (Claude, Voyage, la base de datos).
            // Al usuario no le enseñamos detalles internos; los dejamos en el log para nosotros.
            log.error("Error respondiendo a la pregunta", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("No he podido responder ahora mismo. Inténtalo de nuevo en unos minutos."));
        }
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
