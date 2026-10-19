package com.ragpeat.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.ragpeat.chat.ChatService;
import com.ragpeat.eval.Judge.CheckedClaim;
import com.ragpeat.eval.Judge.Verdict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compara el bot (RAG) con Claude a secas (sin RAG): de cada respuesta, ¿cuántas de las cosas
 * que atribuye a Peat están de verdad en sus artículos?
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar compare
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar compare --eval.limit=3     (solo las 3 primeras)
 */
@Component
public class CompareCommand implements CommandLineRunner {

    private final ChatService rag;
    private final BaselineChat baseline;
    private final Judge judge;
    private final JsonMapper json;
    private final int limit;

    public CompareCommand(ChatService rag, BaselineChat baseline, Judge judge, JsonMapper json,
                          @Value("${eval.limit:1000}") int limit) {
        this.rag = rag;
        this.baseline = baseline;
        this.judge = judge;
        this.json = json;
        this.limit = limit;
    }

    record Comparison(EvalQuestion question, String ragAnswer, List<CheckedClaim> ragClaims,
                      String baselineAnswer, List<CheckedClaim> baselineClaims) {}

    @Override
    public void run(String... args) throws Exception {
        if (!List.of(args).contains("compare")) {
            return;
        }
        List<EvalQuestion> questions = json.readValue(Path.of("eval", "questions.json").toFile(),
                new TypeReference<List<EvalQuestion>>() {});

        List<Comparison> comparisons = new ArrayList<>();
        for (EvalQuestion q : questions.stream().limit(limit).toList()) {
            String ragAnswer = rag.answer(q.question()).text();
            String baselineAnswer = baseline.answer(q.question());
            Comparison c = new Comparison(q,
                    ragAnswer, judge.check(q.question(), ragAnswer),
                    baselineAnswer, judge.check(q.question(), baselineAnswer));
            comparisons.add(c);

            System.out.printf("%-20s %-9s RAG %s · sin RAG %s%n",
                    q.id(), q.type(), counts(c.ragClaims()), counts(c.baselineClaims()));
        }

        System.out.printf("%n=== %d preguntas (afirmaciones sobre Peat: respaldadas / sin respaldo / contradichas)%n",
                comparisons.size());
        System.out.println("RAG:     " + summary(comparisons, Comparison::ragClaims));
        System.out.println("Sin RAG: " + summary(comparisons, Comparison::baselineClaims));

        // Precios por millón de tokens: Haiku 5.5 $0,10/$0,50 (sin RAG) y Sonnet 5.5 $2/$10 (juez).
        double baselineCost = (baseline.inputTokens * 0.10 + baseline.outputTokens * 0.50) / 1_000_000;
        double judgeCost = (judge.inputTokens * 2.0 + judge.outputTokens * 10.0) / 1_000_000;
        System.out.printf("Coste aprox. -> sin RAG: $%.4f · juez: $%.4f%n", baselineCost, judgeCost);

        Path out = Path.of("eval", "results",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-compare.json");
        Files.createDirectories(out.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), comparisons);
        System.out.println("Detalle guardado en " + out);
    }

    private static String counts(List<CheckedClaim> claims) {
        return count(claims, Verdict.SUPPORTED) + "/" + count(claims, Verdict.UNSUPPORTED) + "/" + count(claims, Verdict.CONTRADICTED);
    }

    private static String summary(List<Comparison> comparisons, Function<Comparison, List<CheckedClaim>> side) {
        List<CheckedClaim> all = comparisons.stream().flatMap(c -> side.apply(c).stream()).toList();
        long supported = count(all, Verdict.SUPPORTED);
        return String.format("%d afirmaciones · %d respaldadas (%.0f%%) · %d sin respaldo · %d contradichas",
                all.size(), supported, all.isEmpty() ? 0 : 100.0 * supported / all.size(),
                count(all, Verdict.UNSUPPORTED), count(all, Verdict.CONTRADICTED));
    }

    private static long count(List<CheckedClaim> claims, Verdict verdict) {
        return claims.stream().filter(c -> c.verdict() == verdict).count();
    }
}
