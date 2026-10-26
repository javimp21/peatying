package com.ragpeat.chat;

import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traduce al español las citas literales en inglés de una respuesta (botón "Español" de la página).
 * Va aparte de la respuesta a propósito: la cita original se queda tal cual, y solo quien la quiera
 * en español paga (unas décimas de céntimo) por traducirla.
 */
@Service
public class QuoteTranslator {

    private static final String PROMPT = """
            Traduce al español cada una de estas citas de Ray Peat. Son frases en inglés sobre biología y salud.
            Traducción fiel y natural, sin añadir ni quitar ideas y sin comillas.
            Responde SOLO con un array JSON de strings: una traducción por cita, en el mismo orden.

            """;

    private final AnthropicClient client;
    private final String model;
    private final JsonMapper json;

    public QuoteTranslator(AnthropicClient client, @Value("${anthropic.model}") String model, JsonMapper json) {
        this.client = client;
        this.model = model;
        this.json = json;
    }

    public List<String> toSpanish(List<String> quotes) {
        Message response = client.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(2000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(PROMPT + json.writeValueAsString(quotes))
                .build());
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .reduce("", String::concat);
        // Por si lo envuelve en ```json ... ```: nos quedamos con lo que va del primer [ al último ].
        int start = text.indexOf('['), end = text.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IllegalStateException("La traducción no es un array JSON");
        }
        List<String> translations = json.readValue(text.substring(start, end + 1), new TypeReference<List<String>>() {});
        if (translations.size() != quotes.size()) {
            throw new IllegalStateException("Se esperaban " + quotes.size() + " traducciones y llegaron " + translations.size());
        }
        return translations;
    }
}
