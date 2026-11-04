package com.ragpeat.chat;

import java.util.regex.Pattern;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Averigua en qué idioma está la pregunta, para decírselo a Claude con todas las letras.
 * Las instrucciones están en español y, con idiomas que no son ni español ni inglés (turco, polaco...), el modelo
 * a veces respondía en español aunque el prompt le pidiera usar el idioma de la persona. "El mensaje está en turco"
 * funciona mejor que "fíjate en qué idioma está".
 *
 * Es una llamada aparte, muy corta, al mismo modelo. Se probó antes una librería de detección (Lingua): en modo
 * preciso ocupaba ~670 MB de memoria (el servidor tiene 2 GB) y en modo ligero confundía inglés con lituano.
 */
@Service
public class LanguageHint {

    private static final Logger log = LoggerFactory.getLogger(LanguageHint.class);

    private static final String PROMPT = """
            Which language is this message written in? Reply ONLY with its English name followed by its own name \
            in parentheses, for example: Turkish (Türkçe). If it is an acronym, a name or a single term and you \
            can't tell, reply: unclear.

            Message: """;

    // La web antepone el trozo de respuesta que la persona seleccionó (ver app.js): el idioma lo decide lo que
    // escribe ella después, no el trozo citado ni la frase fija de la web.
    private static final Pattern QUOTE_PREFIX = Pattern.compile(
            "^(Sobre esta parte de tu respuesta anterior: «.*?»|About this part of your previous answer: “.*?”)\\s*",
            Pattern.DOTALL);
    // Solo se pregunta si hay alguna letra que no sea del alfabeto inglés o español (ı, ş, ł, ä, ß, ç, cirílico,
    // árabe...). Con inglés y español el bot ya acertaba, y así casi ninguna pregunta paga el retraso extra.
    private static final Pattern OTHER_LETTERS = Pattern.compile("[\\p{L}&&[^a-zA-ZáéíóúüñÁÉÍÓÚÜÑ]]");

    // "Turkish (Türkçe)", "Polish (polski)", "Chinese (中文)"
    private static final Pattern NAME = Pattern.compile("\\p{L}+ \\([\\p{L}\\p{M} ]+\\)");

    private final AnthropicClient client;
    private final String model;

    public LanguageHint(AnthropicClient client, @Value("${anthropic.model}") String model) {
        this.client = client;
        this.model = model;
    }

    /**
     * El idioma de la pregunta en inglés y en el propio idioma ("Turkish (Türkçe)"), o null si no hace falta o no
     * está claro. Va en inglés y no en español a propósito: con "el mensaje está en turco", en español, el modelo
     * aún respondía en español 2 de cada 6 veces.
     */
    public String of(String question) {
        String own = ownText(question);
        if (own == null) {
            return null;
        }
        try {
            Message response = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(20L)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .addUserMessage(PROMPT + own)
                    .build(), AnthropicConfig.WEB_REQUEST);
            String name = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .reduce("", String::concat)
                    .strip().replaceAll("[.\"«»]", "");
            log.info("Idioma de la pregunta -> {}", name);
            // Algo raro (una frase en vez de un nombre) se ignora: mejor ninguna pista que una equivocada.
            return NAME.matcher(name).matches() && !name.toLowerCase().startsWith("unclear") ? name : null;
        } catch (RuntimeException e) {
            log.warn("No se pudo detectar el idioma: {}", e.getMessage());
            return null; // el bot decide como siempre
        }
    }

    /** Lo que escribió la persona si merece la pena preguntar su idioma; si no, null. */
    static String ownText(String question) {
        if (question == null) {
            return null;
        }
        String own = QUOTE_PREFIX.matcher(question).replaceFirst("").strip();
        if (own.split("\\s+").length < 3 || !OTHER_LETTERS.matcher(own).find()) {
            return null; // una palabra suelta o una sigla, o letras de inglés y español: no hace falta
        }
        return own.length() > 500 ? own.substring(0, 500) : own;
    }
}
