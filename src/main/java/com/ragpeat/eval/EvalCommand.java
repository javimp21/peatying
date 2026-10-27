package com.ragpeat.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.chat.ChatService;
import com.ragpeat.eval.Grader.Grade;
import com.ragpeat.ingest.ArticleFileStore;
import com.ragpeat.ingest.BookFileStore;
import com.ragpeat.ingest.InterviewFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pasa todas las preguntas de eval/questions.json por el bot y dice cuántas aprueba.
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar eval
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar eval --anthropic.model=claude-sonnet-5-5   (otro modelo)
 *
 * Guarda el detalle en eval/results/ para poder comparar ejecuciones.
 */
@Component
public class EvalCommand implements CommandLineRunner {

    private static final double INPUT_PRICE = 0.10, OUTPUT_PRICE = 0.50; // Haiku 5.5, $ por millón de tokens

    private final ChatService chatService;
    private final ArticleFileStore store;
    private final InterviewFileStore interviewStore;
    private final BookFileStore bookStore;
    private final JsonMapper json;
    private final String model;

    public EvalCommand(ChatService chatService, ArticleFileStore store, InterviewFileStore interviewStore,
                       BookFileStore bookStore, JsonMapper json, @Value("${anthropic.model}") String model) {
        this.chatService = chatService;
        this.store = store;
        this.interviewStore = interviewStore;
        this.bookStore = bookStore;
        this.json = json;
        this.model = model;
    }

    /** Lo que guardamos de cada pregunta en el archivo de resultados. */
    record Result(EvalQuestion question, String answer, List<String> cited, Grade grade,
                  int searches, long inputTokens, long outputTokens, long millis) {}

    @Override
    public void run(String... args) throws Exception {
        if (!List.of(args).contains("eval")) {
            return;
        }
        List<EvalQuestion> questions = json.readValue(Path.of("eval", "questions.json").toFile(),
                new TypeReference<List<EvalQuestion>>() {});
        Grader grader = new Grader(store.loadAll(), interviewStore.loadAll(), bookStore.loadAll());

        List<Result> results = new ArrayList<>();
        for (EvalQuestion q : questions) {
            long start = System.currentTimeMillis();
            ChatAnswer answer = chatService.answer(q.question());
            long millis = System.currentTimeMillis() - start;

            Grade grade = grader.grade(q, answer);
            List<String> cited = answer.citedSources().values().stream().map(s -> s.url()).distinct().toList();
            results.add(new Result(q, answer.text(), cited, grade, answer.searches(),
                    answer.inputTokens(), answer.outputTokens(), millis));

            System.out.printf("%-4s %-20s %-9s %s%n", grade.passed() ? "OK" : "FALLO", q.id(), q.type(),
                    grade.passed() ? "" : grade.reason() + (grade.invalidQuotes().isEmpty() ? "" : " " + grade.invalidQuotes())
                            + (grade.interviewQuotes().isEmpty() ? "" : " " + grade.interviewQuotes()));
        }
        printSummary(results);

        Path out = Path.of("eval", "results",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-" + model + ".json");
        Files.createDirectories(out.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), results);
        System.out.println("Detalle guardado en " + out);
    }

    private void printSummary(List<Result> results) {
        long passed = results.stream().filter(r -> r.grade().passed()).count();
        int quotes = results.stream().mapToInt(r -> r.grade().quotes()).sum();
        int invalid = results.stream().mapToInt(r -> r.grade().invalidQuotes().size()).sum();
        int fromInterviews = results.stream().mapToInt(r -> r.grade().interviewQuotes().size()).sum();
        double cost = results.stream()
                .mapToDouble(r -> (r.inputTokens() * INPUT_PRICE + r.outputTokens() * OUTPUT_PRICE) / 1_000_000).sum();
        double avgSeconds = results.stream().mapToLong(Result::millis).average().orElse(0) / 1000;

        System.out.printf("%n=== %s: %d/%d aprobadas (%.0f%%)%n", model, passed, results.size(), 100.0 * passed / results.size());
        System.out.printf("Citas literales: %d, de ellas inventadas: %d, de entrevistas: %d%n", quotes, invalid, fromInterviews);
        System.out.printf("Coste total: ~$%.4f · tiempo medio por pregunta: %.1f s%n%n", cost, avgSeconds);
    }
}
