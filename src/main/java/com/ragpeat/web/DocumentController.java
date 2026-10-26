package com.ragpeat.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.ragpeat.chat.ConversationRepository;
import com.ragpeat.chat.DocumentReader;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * POST /api/document (multipart: file, conversationId?, internal?): adjuntar un PDF o una foto a la conversación.
 * Se saca su texto (DocumentReader), se guarda con la conversación sin datos personales, y el archivo se descarta.
 * Si aún no hay conversación, se crea: así la primera pregunta ya lo tiene delante.
 */
@RestController
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    static final long MAX_BYTES = 5 * 1024 * 1024;

    private final DocumentReader reader;
    private final ConversationRepository conversations;
    private final ClientInfo clientInfo;
    /** Leer un documento cuesta más que una pregunta: límite propio, aparte del de preguntas. */
    private final RateLimiter limiter = new RateLimiter(5, Duration.ofHours(1), 300, Clock.systemUTC());

    public DocumentController(DocumentReader reader, ConversationRepository conversations, ClientInfo clientInfo) {
        this.reader = reader;
        this.conversations = conversations;
        this.clientInfo = clientInfo;
    }

    record DocumentResponse(UUID conversationId, String name) {}

    record ErrorResponse(String error) {}

    @PostMapping("/api/document")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) UUID conversationId,
                                    @RequestParam(defaultValue = "false") boolean internal,
                                    HttpServletRequest http) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(new ErrorResponse("El archivo tiene que pesar menos de 5 MB."));
        }
        byte[] data = file.getBytes();
        Optional<DocumentReader.Kind> kind = DocumentReader.detect(data);
        if (kind.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Solo se pueden adjuntar PDF o fotos (JPG, PNG o WebP)."));
        }
        if (!limiter.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Has adjuntado muchos documentos seguidos. Prueba dentro de un rato."));
        }

        Optional<String> text;
        try {
            text = reader.read(data, kind.get());
        } catch (RuntimeException e) {
            log.warn("No se pudo leer un documento: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("No he podido leer el documento ahora. Prueba otra vez."));
        }
        if (text.isEmpty()) {
            return ResponseEntity.unprocessableEntity()
                    .body(new ErrorResponse("No he encontrado texto legible en el documento. Si es una foto, prueba con más luz."));
        }

        UUID id = conversationId != null && conversations.exists(conversationId)
                ? conversationId
                : conversations.create(clientInfo.visitorHash(http), internal);
        conversations.saveDocument(id, displayName(file.getOriginalFilename()), text.get());
        return ResponseEntity.ok(new DocumentResponse(id, displayName(file.getOriginalFilename())));
    }

    /** Solo el nombre, corto y sin rutas ni caracteres raros (se muestra en la página y en el panel). */
    static String displayName(String original) {
        String name = original == null ? "" : original.replaceAll(".*[/\\\\]", "").replaceAll("[^\\p{L}\\p{N} ._()-]", "").strip();
        if (name.isEmpty()) name = "documento";
        return name.length() > 60 ? name.substring(0, 57) + "…" : name;
    }
}
