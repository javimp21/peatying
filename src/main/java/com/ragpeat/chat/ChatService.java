package com.ragpeat.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.ragpeat.search.SearchResult;
import com.ragpeat.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * El "RAG agéntico": Claude decide qué buscar, lee los resultados, puede volver a buscar,
 * y al final responde citando los fragmentos.
 *
 * El bucle (lo más importante de entender):
 *   1. Mandamos a Claude la pregunta + la descripción de la herramienta search_peat.
 *   2. Claude responde de una de dos formas:
 *        a) "quiero usar search_peat con estos datos" (tool_use)  -> la ejecutamos NOSOTROS
 *           y le devolvemos los resultados (tool_result). Volvemos al paso 2.
 *        b) un texto normal -> es la respuesta final. Terminamos.
 * Claude nunca toca la base de datos: solo pide búsquedas, y nuestro código las hace.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_SEARCHES = 3;      // lo mismo que dice el prompt; aquí se hace cumplir
    private static final int MAX_TURNS = 6;         // red de seguridad contra un bucle infinito
    private static final int RESULTS_PER_SEARCH = 5;
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]"); // "[3]" -> captura "3"

    /** La herramienta tal como la ve Claude: nombre, para qué sirve y qué datos necesita (JSON Schema). */
    private static final Tool SEARCH_TOOL = Tool.builder()
            .name("search_peat")
            .description("Busca en los artículos de Ray Peat (en inglés) y devuelve los "
                    + RESULTS_PER_SEARCH + " fragmentos más relevantes, numerados para poder citarlos.")
            .inputSchema(Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("query", JsonValue.from(Map.of(
                                    "type", "string",
                                    "description", "La pregunta reformulada en inglés, para la búsqueda por significado.")))
                            .putAdditionalProperty("keywords", JsonValue.from(Map.of(
                                    "type", "string",
                                    "description", "De 2 a 6 palabras clave en inglés separadas por espacios.")))
                            .build())
                    .required(List.of("query", "keywords"))
                    .build())
            .build();

    private final AnthropicClient claude;
    private final SearchService searchService;
    private final String model;
    private final String systemPrompt;

    public ChatService(AnthropicClient claude, SearchService searchService,
                       @Value("${anthropic.model}") String model,
                       @Value("classpath:prompts/system.md") Resource systemPromptFile) throws IOException {
        this.claude = claude;
        this.searchService = searchService;
        this.model = model;
        // El prompt está en un archivo aparte (src/main/resources/prompts/system.md) para poder
        // leerlo y cambiarlo cómodamente, sin tocar Java.
        this.systemPrompt = systemPromptFile.getContentAsString(StandardCharsets.UTF_8);
    }

    public ChatAnswer answer(String question) {
        List<SearchResult> sources = new ArrayList<>(); // todos los fragmentos vistos; su posición+1 es su número [n]
        int searches = 0;
        long inputTokens = 0, outputTokens = 0;

        // El builder guarda la conversación entera. Cada vuelta le añadimos mensajes y lo volvemos a enviar:
        // la API no recuerda nada entre llamadas, así que hay que mandarle siempre el historial completo.
        MessageCreateParams.Builder request = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(systemPrompt)
                // effort: cuánto "piensa" el modelo antes de responder. MEDIUM es un buen equilibrio para empezar.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .addTool(SEARCH_TOOL)
                .addUserMessage(question);

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            Message response = claude.messages().create(request.build());
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();

            if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                return new ChatAnswer("Lo siento, no puedo responder a esa pregunta.", new TreeMap<>(), searches,
                        inputTokens, outputTokens);
            }

            // Guardamos la respuesta de Claude en el historial TAL CUAL (incluidos sus bloques internos).
            request.addMessage(response);

            List<ToolUseBlock> toolUses = response.content().stream()
                    .flatMap(block -> block.toolUse().stream())
                    .toList();

            // Caso b): no pide herramientas -> es la respuesta final.
            if (toolUses.isEmpty()) {
                String text = response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                return new ChatAnswer(text, citedIn(text, sources), searches, inputTokens, outputTokens);
            }

            // Caso a): ejecutamos cada búsqueda pedida y devolvemos todos los resultados en UN mensaje.
            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ToolUseBlock toolUse : toolUses) {
                String result;
                if (searches >= MAX_SEARCHES) {
                    result = "Ya has hecho " + MAX_SEARCHES + " búsquedas. Responde con lo que tienes.";
                } else {
                    searches++;
                    result = runSearch(toolUse, sources);
                }
                toolResults.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id()) // así Claude sabe a qué petición corresponde cada resultado
                        .content(result)
                        .build()));
            }
            request.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER) // en la API, los resultados de herramientas van en un mensaje "user"
                    .contentOfBlockParams(toolResults)
                    .build());
        }
        throw new IllegalStateException("Claude no terminó en " + MAX_TURNS + " turnos");
    }

    /** Lee lo que Claude pidió buscar, busca, y le devuelve los fragmentos como texto numerado. */
    private String runSearch(ToolUseBlock toolUse, List<SearchResult> sources) {
        // La entrada llega como JSON: {"query": "...", "keywords": "..."}. convert() la pasa al record.
        SearchInput input = toolUse._input().convert(SearchInput.class);
        log.info("Claude busca -> query: \"{}\" | keywords: \"{}\"", input.query(), input.keywords());

        StringBuilder text = new StringBuilder();
        for (SearchResult result : searchService.search(input.query(), input.keywords(), RESULTS_PER_SEARCH)) {
            int number = numberOf(result, sources);
            text.append("[").append(number).append("] ").append(result.title())
                    .append(" (").append(result.url()).append(")\n")
                    .append(result.content()).append("\n\n");
        }
        return text.isEmpty() ? "No se encontró nada." : text.toString();
    }

    /**
     * Busca en la respuesta los números citados ([1], [3]...) y devuelve solo esos fragmentos.
     * Así la lista de fuentes no muestra fragmentos que se encontraron pero no se usaron.
     * Un número que no corresponda a ningún fragmento (una cita inventada) se ignora.
     */
    static SortedMap<Integer, SearchResult> citedIn(String text, List<SearchResult> sources) {
        SortedMap<Integer, SearchResult> cited = new TreeMap<>(); // TreeMap: ordenado por número
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 1 && number <= sources.size()) {
                cited.put(number, sources.get(number - 1));
            }
        }
        return cited;
    }

    /** Los datos que Claude manda al usar search_peat (los mismos campos que el JSON Schema de SEARCH_TOOL). */
    record SearchInput(String query, String keywords) {}

    /** Si el fragmento ya salió en una búsqueda anterior, conserva su número; si no, le da uno nuevo. */
    private int numberOf(SearchResult result, List<SearchResult> sources) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).chunkId() == result.chunkId()) {
                return i + 1;
            }
        }
        sources.add(result);
        return sources.size();
    }
}
