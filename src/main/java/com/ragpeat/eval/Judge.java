package com.ragpeat.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.ragpeat.search.SearchResult;
import com.ragpeat.search.SearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * El juez comprueba, afirmación por afirmación, si lo que una respuesta atribuye a Peat
 * está en sus artículos. Lo hace en dos pasos:
 *   1. extractClaims: saca las afirmaciones sobre Peat de la respuesta.
 *   2. verify: para cada afirmación busca en TODOS los artículos los fragmentos más relacionados
 *      con ella, y decide si la respaldan, la contradicen o no dicen nada.
 * Es la misma vara para cualquier respuesta, venga del RAG o no.
 *
 * Usa un modelo más potente que el que responde, y "salidas estructuradas": la API obliga al modelo
 * a devolver un JSON con la forma del record, y el SDK lo convierte. Nada de interpretar texto libre.
 */
@Component
public class Judge {

    private static final int EVIDENCE_PER_CLAIM = 6;

    public enum Verdict { SUPPORTED, UNSUPPORTED, CONTRADICTED }

    record Claims(@JsonPropertyDescription("Afirmaciones atribuidas a Peat, una por elemento") List<String> claims) {}

    record Check(Verdict verdict,
                 @JsonPropertyDescription("Frase de la evidencia en la que te basas; vacío si es UNSUPPORTED") String evidence) {}

    record Checks(@JsonPropertyDescription("Un resultado por afirmación, en el mismo orden") List<Check> checks) {}

    /** Una afirmación ya comprobada. */
    public record CheckedClaim(String claim, Verdict verdict, String evidence) {}

    private final AnthropicClient claude;
    private final SearchService searchService;
    private final String judgeModel;
    private final String claimsPrompt;
    private final String verifyPrompt;

    /** Tokens gastados por el juez en esta ejecución (para saber cuánto cuesta evaluar). */
    long inputTokens, outputTokens;

    public Judge(AnthropicClient claude, SearchService searchService,
                 @Value("${eval.judge-model}") String judgeModel,
                 @Value("classpath:prompts/judge-claims.md") Resource claimsPrompt,
                 @Value("classpath:prompts/judge-verify.md") Resource verifyPrompt) throws IOException {
        this.claude = claude;
        this.searchService = searchService;
        this.judgeModel = judgeModel;
        this.claimsPrompt = claimsPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.verifyPrompt = verifyPrompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public List<CheckedClaim> check(String question, String answer) {
        List<String> claims = ask(claimsPrompt,
                "PREGUNTA:\n" + question + "\n\nRESPUESTA:\n<answer>\n" + answer + "\n</answer>",
                Claims.class).claims();
        if (claims.isEmpty()) {
            return List.of();
        }

        StringBuilder input = new StringBuilder();
        for (int i = 0; i < claims.size(); i++) {
            input.append("=== AFIRMACIÓN ").append(i + 1).append(": ").append(claims.get(i)).append("\nEVIDENCIA:\n");
            // La búsqueda se hace con la AFIRMACIÓN, no con la pregunta: así encontramos dónde habla
            // Peat de eso en concreto, aunque nuestro bot no lo hubiera encontrado.
            for (SearchResult r : searchService.search(claims.get(i), null, EVIDENCE_PER_CLAIM)) {
                input.append("--- ").append(r.title()).append("\n").append(r.content()).append("\n");
            }
            input.append("\n");
        }
        List<Check> checks = ask(verifyPrompt, input.toString(), Checks.class).checks();

        // Por si el juez devolviera una lista de distinto tamaño: emparejamos solo hasta el más corto.
        int n = Math.min(claims.size(), checks.size());
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new CheckedClaim(claims.get(i), checks.get(i).verdict(), checks.get(i).evidence()))
                .toList();
    }

    /** Una llamada al juez que devuelve directamente el record pedido. */
    private <T> T ask(String systemPrompt, String userMessage, Class<T> type) {
        var response = claude.messages().create(MessageCreateParams.builder()
                .model(judgeModel)
                .maxTokens(16000L)
                .system(systemPrompt)
                .outputConfig(type)
                .addUserMessage(userMessage)
                .build());
        inputTokens += response.usage().inputTokens();
        outputTokens += response.usage().outputTokens();

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El juez no devolvió resultado"));
    }
}
