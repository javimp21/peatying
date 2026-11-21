package com.ragpeat.web;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.ragpeat.chat.ChatService;
import com.ragpeat.chat.ConversationRepository;
import com.ragpeat.chat.ConversationRepository.FirstAnswer;
import com.ragpeat.chat.FollowUps;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/followups con {"conversationId": "...", "messageId": 123}: las preguntas de seguimiento que la página
 * ofrece como botones bajo la PRIMERA respuesta de una conversación. Responde {"questions": [...]} (vacío si no hay).
 *
 * Todo sale de la base de datos y no de lo que manda el navegador: la pregunta y la respuesta guardadas y los
 * fragmentos que citó. Se generan una sola vez y se guardan (también si salen vacías) para no repetir la llamada.
 * Solo vale para el primer mensaje del bot de la conversación, con el id secreto de la conversación como en los votos.
 */
@RestController
public class FollowUpsController {

    private static final Logger log = LoggerFactory.getLogger(FollowUpsController.class);

    static final int MAX_CONCURRENT = 8;

    private final ConversationRepository conversations;
    private final ChatService chatService;
    private final FollowUps followUps;
    private final ClientInfo clientInfo;
    private final JsonMapper json;
    /** Contador propio: sugerir preguntas no debe gastar el cupo de preguntas ni el de traducciones. */
    private final RateLimiter rateLimiter = new RateLimiter(30, Duration.ofMinutes(60), 1000, Clock.systemUTC());
    private final Semaphore generating = new Semaphore(MAX_CONCURRENT);

    public FollowUpsController(ConversationRepository conversations, ChatService chatService, FollowUps followUps,
                               ClientInfo clientInfo, JsonMapper json) {
        this.conversations = conversations;
        this.chatService = chatService;
        this.followUps = followUps;
        this.clientInfo = clientInfo;
        this.json = json;
    }

    record FollowUpsRequest(UUID conversationId, Long messageId) {}

    record FollowUpsResponse(List<String> questions) {}

    record ErrorResponse(String error) {}

    @PostMapping("/api/followups")
    public ResponseEntity<?> followUps(@RequestBody FollowUpsRequest request, HttpServletRequest http) {
        if (request.conversationId() == null || request.messageId() == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Faltan datos."));
        }
        Optional<FirstAnswer> found = conversations.firstAnswer(request.conversationId(), request.messageId());
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        FirstAnswer first = found.get();
        if (first.citedChunks() == null || first.citedChunks().isBlank()) {
            return ResponseEntity.ok(new FollowUpsResponse(List.of()));
        }
        if (first.suggestions() != null) {
            return ResponseEntity.ok(new FollowUpsResponse(stored(first.suggestions())));
        }
        // Antes del límite por IP: si no hay hueco, no se le gasta una petición.
        if (!generating.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30")
                    .body(new ErrorResponse("Hay muchas peticiones a la vez."));
        }
        try {
            if (!rateLimiter.tryAcquire(clientInfo.ip(http))) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse("Demasiadas peticiones."));
            }
            var sources = chatService.sourcesOf(ConversationRepository.decodeCited(first.citedChunks()));
            List<String> questions = sources.isEmpty() ? List.of()
                    : followUps.suggest(first.question(), first.answer(), sources.values());
            conversations.saveSuggestions(first.id(), json.writeValueAsString(questions));
            return ResponseEntity.ok(new FollowUpsResponse(questions));
        } catch (RuntimeException e) {
            log.warn("No se pudieron preparar las preguntas de seguimiento: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("No disponible ahora."));
        } finally {
            generating.release();
        }
    }

    private List<String> stored(String suggestionsJson) {
        try {
            return json.readValue(suggestionsJson, new TypeReference<List<String>>() {});
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
