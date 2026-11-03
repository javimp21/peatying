package com.ragpeat.chat;

import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Pasa a texto una pregunta dicha en voz alta (botón del micrófono del chat), con Whisper en Groq: el mismo modelo
 * con el que se transcribieron las entrevistas. Entiende cualquier idioma sin decírselo.
 * El audio no se guarda en ningún sitio: se manda, vuelve el texto y la persona lo revisa antes de enviarlo.
 */
@Service
public class Transcriber {

    /** Los formatos que graban los navegadores, reconocidos por sus primeros bytes y no por lo que diga el navegador. */
    public enum Kind {
        WEBM("audio.webm"),   // Chrome, Firefox, Android
        MP4("audio.m4a"),     // Safari (iPhone y Mac)
        OGG("audio.ogg");

        final String fileName;   // Groq decide el formato por la extensión

        Kind(String fileName) {
            this.fileName = fileName;
        }
    }

    // Una pista de vocabulario: sin ella, Whisper escribe "Ray Pete" o "pregnenolona" mal.
    private static final String PROMPT = "Ray Peat, progesterone, pregnenolone, thyroid, TSH, T3, serotonin, "
            + "estrogen, cortisol, aspirin, gelatin, PUFA. Progesterona, pregnenolona, tiroides, estrógenos.";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String text) {}

    private final RestClient http;
    private final boolean enabled;

    public Transcriber(RestClient.Builder builder, @Value("${groq.api-key:}") String apiKey) {
        this.enabled = !apiKey.isBlank();
        this.http = builder
                .baseUrl("https://api.groq.com/openai/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /** Sin clave de Groq (por ejemplo, en local sin configurar) el botón del micrófono no se ofrece. */
    public boolean enabled() {
        return enabled;
    }

    public static Optional<Kind> detect(byte[] data) {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3) return Optional.of(Kind.WEBM);
        if (data.length >= 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') return Optional.of(Kind.MP4);
        if (data.length >= 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') return Optional.of(Kind.OGG);
        return Optional.empty();
    }

    /** El texto dicho, sin espacios de sobra; vacío si no se entendió nada. */
    public String transcribe(byte[] audio, Kind kind) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // Un recurso con nombre de archivo: así Spring lo manda como archivo, y Groq ve la extensión.
        body.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return kind.fileName;
            }
        });
        body.add("model", "whisper-large-v3-turbo");
        body.add("response_format", "json");
        body.add("prompt", PROMPT);
        Response response = http.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Response.class);
        return response == null || response.text() == null ? "" : fixName(response.text().strip());
    }

    // Aun con la pista de vocabulario, a veces sale "Ray Pede", "Ray Pid" o, dicho en español, "Rey Pít": "Ray" o "Rey"
    // y una P, vocales y una d o t al final. Detrás de "Rey" sin vocal final, para no tocar "el rey pide".
    private static final Pattern NAME = Pattern.compile(
            "(?iu)\\b(?:ray\\s+p[eiayíé]+[dt]{1,2}e?|rey\\s+p[eiayíé]+[dt]{1,2})(?![\\p{L}])");

    static String fixName(String text) {
        return NAME.matcher(text).replaceAll("Ray Peat");
    }
}
