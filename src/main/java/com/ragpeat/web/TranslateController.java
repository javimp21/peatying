package com.ragpeat.web;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.ragpeat.chat.QuoteTranslator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/translate con {"texts": ["...", "..."]}: traduce al español las citas en inglés de una respuesta.
 * Responde {"translations": ["...", "..."]} en el mismo orden.
 */
@RestController
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    // Una respuesta tiene pocas citas y cortas (el prompt pide frases cortas). Más que esto es abuso.
    static final int MAX_TEXTS = 12;
    static final int MAX_TEXT_LENGTH = 400;

    private final QuoteTranslator translator;
    private final ClientInfo clientInfo;
    /** Contador propio, aparte del de preguntas: traducir no debe gastar el cupo de preguntas. */
    private final RateLimiter rateLimiter = new RateLimiter(40, Duration.ofMinutes(60), 1000, Clock.systemUTC());

    public TranslateController(QuoteTranslator translator, ClientInfo clientInfo) {
        this.translator = translator;
        this.clientInfo = clientInfo;
    }

    record TranslateRequest(List<String> texts) {}

    record TranslateResponse(List<String> translations) {}

    record ErrorResponse(String error) {}

    @PostMapping("/api/translate")
    public ResponseEntity<?> translate(@RequestBody TranslateRequest request, HttpServletRequest http) {
        List<String> texts = request.texts();
        if (texts == null || texts.isEmpty() || texts.size() > MAX_TEXTS
                || texts.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > MAX_TEXT_LENGTH)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Nada que traducir."));
        }
        if (!rateLimiter.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Demasiadas traducciones seguidas. Prueba dentro de un rato."));
        }
        try {
            return ResponseEntity.ok(new TranslateResponse(translator.toSpanish(texts)));
        } catch (RuntimeException e) {
            log.warn("No se pudo traducir: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("No se pudo traducir ahora."));
        }
    }
}
