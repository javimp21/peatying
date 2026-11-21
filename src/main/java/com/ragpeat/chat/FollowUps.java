package com.ragpeat.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.ragpeat.search.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Las dos preguntas de seguimiento que se ofrecen como botones bajo la primera respuesta de una conversación.
 * Va aparte de la respuesta a propósito: el bot no termina sus respuestas con preguntas, y esta llamada corta
 * (unas décimas de céntimo) solo se hace una vez por conversación y sin que la persona espere.
 */
@Service
public class FollowUps {

    private static final Logger log = LoggerFactory.getLogger(FollowUps.class);

    /**
     * Las dos preguntas ocupan unos 60 tokens, pero el modelo "piensa" antes aunque el esfuerzo sea bajo y ese
     * pensamiento cuenta: con 150 se quedaba sin sitio y no escribía nada (stop_reason max_tokens).
     */
    static final long MAX_TOKENS = 1000L;
    static final int MAX_QUESTIONS = 2;
    static final int MAX_QUESTION_CHARS = 100;
    /** Cuánto texto de las fuentes se le enseña al modelo: lo justo para saber qué se puede responder. */
    static final int MAX_SOURCE_CHARS = 6000;

    private static final String PROMPT = """
            A reader just got this answer, based on passages from Ray Peat's work (shown below), in a chatbot.
            Suggest exactly 2 follow-up questions the reader, a curious person, might ask next.
            Rules:
            - Write them in the language of the answer.
            - Short: at most 70 characters, phrased the way a person would type them.
            - Each one must be answerable with what the passages say, and must not repeat what the answer already covers.
            - No personal questions and nothing about doses or advice for the reader themselves.
            - One question per line, no numbering, no bullets, nothing else.
            """;

    // Viñeta o numeración al principio de la línea: «- », «* », «• », «1. », «2) ».
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*•]|\\d+[.)])\\s*");

    private final AnthropicClient client;
    private final String model;

    public FollowUps(AnthropicClient client, @Value("${anthropic.model}") String model) {
        this.client = client;
        this.model = model;
    }

    /** Hasta 2 preguntas; vacío si el modelo falla o no da nada utilizable. */
    public List<String> suggest(String question, String answer, Collection<SearchResult> sources) {
        try {
            Message response = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .addUserMessage(PROMPT + "\nQuestion: " + question + "\n\nAnswer:\n" + answer
                            + "\n\nPassages:\n" + passages(sources))
                    .build(), AnthropicConfig.WEB_REQUEST);
            String raw = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .reduce("", String::concat);
            return parse(raw);
        } catch (RuntimeException e) {
            // Sin texto de la persona en el log: solo el tipo de fallo.
            log.warn("No se pudieron generar las preguntas de seguimiento: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Los fragmentos citados (título y texto, también los de libros: esto no sale al navegador), recortados en total. */
    static String passages(Collection<SearchResult> sources) {
        StringBuilder text = new StringBuilder();
        for (SearchResult source : sources) {
            String piece = "[" + source.title() + "]\n" + source.content() + "\n\n";
            int room = MAX_SOURCE_CHARS - text.length();
            if (room <= 0) {
                break;
            }
            text.append(piece.length() <= room ? piece : piece.substring(0, room));
        }
        return text.toString();
    }

    /** Las líneas que son una pregunta (acaban en «?» o «？»), sin viñetas ni números, no demasiado largas; máximo 2. */
    static List<String> parse(String text) {
        List<String> questions = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = LIST_MARKER.matcher(raw.strip()).replaceFirst("").strip();
            if ((line.endsWith("?") || line.endsWith("？")) && line.length() <= MAX_QUESTION_CHARS) {
                questions.add(line);
                if (questions.size() == MAX_QUESTIONS) {
                    break;
                }
            }
        }
        return questions;
    }
}
