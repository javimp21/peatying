package com.ragpeat.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.ragpeat.chat.Transcriber;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * POST /api/transcribe (multipart: audio): la pregunta grabada con el micrófono, de vuelta como texto.
 * No crea conversación ni guarda nada: el texto vuelve a la caja de la pregunta y la persona decide si lo envía.
 */
@RestController
public class TranscribeController {

    private static final Logger log = LoggerFactory.getLogger(TranscribeController.class);
    /** Un minuto de voz pesa unos 0,5 MB en Chrome y hasta ~1 MB en Safari: 3 MB dejan margen sin abrir la puerta a más. */
    static final long MAX_BYTES = 3 * 1024 * 1024;

    private final Transcriber transcriber;
    private final ClientInfo clientInfo;
    /**
     * Cada transcripción cuesta según los minutos de audio, y un script puede mandar audios muy comprimidos de muchos
     * minutos: topes bajos. 30 por persona y día sobran para preguntar por voz; 300 en total, de sobra para el uso real.
     */
    private final RateLimiter limiter = new RateLimiter(20, Duration.ofHours(1), 30, 300, Clock.systemUTC());

    public TranscribeController(Transcriber transcriber, ClientInfo clientInfo) {
        this.transcriber = transcriber;
        this.clientInfo = clientInfo;
    }

    record TranscribeResponse(String text) {}

    record ErrorResponse(String error) {}

    /** La página pregunta si hay micrófono: sin clave de Groq configurada, no se enseña el botón. */
    @GetMapping("/api/transcribe")
    public Map<String, Boolean> available() {
        return Map.of("enabled", transcriber.enabled());
    }

    @PostMapping("/api/transcribe")
    public ResponseEntity<?> transcribe(@RequestParam("audio") MultipartFile audio, HttpServletRequest http)
            throws IOException {
        if (!transcriber.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("voice-off"));
        }
        if (audio.isEmpty() || audio.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(new ErrorResponse("too-long"));
        }
        byte[] data = audio.getBytes();
        Optional<Transcriber.Kind> kind = Transcriber.detect(data);
        if (kind.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("bad-format"));
        }
        if (!limiter.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse("too-many"));
        }
        try {
            String text = transcriber.transcribe(data, kind.get());
            if (text.isEmpty()) {
                return ResponseEntity.unprocessableEntity().body(new ErrorResponse("nothing-heard"));
            }
            return ResponseEntity.ok(new TranscribeResponse(text));
        } catch (RuntimeException e) {
            log.warn("No se pudo transcribir un audio: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("failed"));
        }
    }
}
