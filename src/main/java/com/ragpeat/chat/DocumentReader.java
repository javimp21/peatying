package com.ragpeat.chat;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Saca el texto de un documento que adjunta alguien (analítica, informe...): PDF o foto.
 * Claude lee el PDF o la imagen directamente (también fotos de papel y tablas) y devuelve el texto.
 * El archivo no se guarda en ningún sitio; lo que se guarda es este texto, ya sin datos personales.
 */
@Service
public class DocumentReader {

    /** Texto máximo que se guarda: de sobra para una analítica o un informe de varias páginas. */
    static final int MAX_TEXT = 8000;

    private static final String PROMPT = """
            Transcribe el texto de este documento (por ejemplo, una analítica o un informe médico) tal cual,
            sin resumir ni interpretar. En las tablas de resultados, una línea por prueba con este formato:
            "Prueba: valor unidad (valores de referencia)". Omite cabeceras de laboratorio, logotipos y pies de página.
            NO transcribas nada que identifique a una persona: nombres (del paciente, médicos o personal del laboratorio),
            números de documento, historia o cobertura, fechas de nacimiento, teléfonos, direcciones o emails. Pon en su
            lugar [dato personal]. Sí puedes dejar el sexo y la edad.
            Responde SOLO con el texto transcrito. Si no hay texto legible, responde exactamente: SIN TEXTO
            """;

    /** Los tipos que aceptamos, reconocidos por los primeros bytes del archivo y no por lo que diga el navegador. */
    public enum Kind { PDF, JPEG, PNG, WEBP }

    private final AnthropicClient client;
    private final String model;

    public DocumentReader(AnthropicClient client, @Value("${anthropic.model}") String model) {
        this.client = client;
        this.model = model;
    }

    public static Optional<Kind> detect(byte[] data) {
        if (startsWith(data, 0, "%PDF".getBytes())) return Optional.of(Kind.PDF);
        if (startsWith(data, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) return Optional.of(Kind.JPEG);
        if (startsWith(data, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) return Optional.of(Kind.PNG);
        if (startsWith(data, 0, "RIFF".getBytes()) && startsWith(data, 8, "WEBP".getBytes())) return Optional.of(Kind.WEBP);
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    /** El texto del documento, ya sin datos personales; vacío si no se pudo leer nada. */
    public Optional<String> read(byte[] data, Kind kind) {
        String base64 = Base64.getEncoder().encodeToString(data);
        ContentBlockParam file = switch (kind) {
            case PDF -> ContentBlockParam.ofDocument(Base64PdfSource.builder().data(base64).build());
            case JPEG -> image(base64, Base64ImageSource.MediaType.IMAGE_JPEG);
            case PNG -> image(base64, Base64ImageSource.MediaType.IMAGE_PNG);
            case WEBP -> image(base64, Base64ImageSource.MediaType.IMAGE_WEBP);
        };
        Message response = client.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .addUserMessageOfBlockParams(List.of(file, ContentBlockParam.ofText(PROMPT)))
                .build());
        String text = response.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text)
                .reduce("", String::concat).strip();
        if (text.isEmpty() || text.equals("SIN TEXTO")) {
            return Optional.empty();
        }
        String clean = PersonalData.redact(text);
        return Optional.of(clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) + "\n[…documento recortado]" : clean);
    }

    private static ContentBlockParam image(String base64, Base64ImageSource.MediaType type) {
        return ContentBlockParam.ofImage(Base64ImageSource.builder().data(base64).mediaType(type).build());
    }
}
