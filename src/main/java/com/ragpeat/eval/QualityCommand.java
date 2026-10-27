package com.ragpeat.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.chat.ChatListener;
import com.ragpeat.chat.ChatService;
import com.ragpeat.chat.Turn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Examen de calidad: ¿las respuestas son útiles para quien pregunta? Pasa las preguntas reales de
 * eval/quality.json por el bot y un juez (Sonnet) puntúa cada respuesta: si responde a lo que se pide,
 * si mete cosas que no vienen a cuento, avisos innecesarios, formato y fidelidad a los fragmentos citados.
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar quality
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar quality --chat.system-prompt=file:otro.md     (otro prompt)
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar quality --anthropic.model=claude-sonnet-5-5   (otro modelo)
 *
 * Guarda el detalle en eval/results/ para poder comparar ejecuciones.
 */
@Component
public class QualityCommand implements CommandLineRunner {

    private static final int THREADS = 4;

    /** Una pregunta del examen. previous: preguntas anteriores de la misma conversación (se responden antes). */
    record QualityQuestion(String id, List<String> previous, String question, String expect) {}

    record Review(@JsonPropertyDescription("1 a 5: ¿responde a lo que la persona quiere?") int answers,
                  @JsonPropertyDescription("Partes que no ayudan a responder a esta pregunta") List<String> offTopic,
                  @JsonPropertyDescription("Avisos o aclaraciones innecesarias") List<String> needlessCaveats,
                  boolean formatOk,
                  String formatNote,
                  @JsonPropertyDescription("Lo atribuido a Peat que no está en los fragmentos citados") List<String> unsupported,
                  @JsonPropertyDescription("Nota global de 1 a 10") int score,
                  String comment) {}

    /** Un fragmento que recibió el bot, resumido para el archivo de resultados. */
    record Seen(int number, String slug, String content) {}

    record Result(QualityQuestion question, String answer, Review review, List<String> queries, List<Seen> seen,
                  long inputTokens, long outputTokens, long millis) {}

    private final ChatService chatService;
    private final AnthropicClient claude;
    private final JsonMapper json;
    private final String model;
    private final String judgeModel;
    private final String judgePrompt;
    private final String promptName;
    private final int limit;
    private final boolean judge;

    private long judgeInput, judgeOutput;

    public QualityCommand(ChatService chatService, AnthropicClient claude, JsonMapper json,
                          @Value("${anthropic.model}") String model,
                          @Value("${eval.judge-model}") String judgeModel,
                          @Value("classpath:prompts/judge-quality.md") Resource judgePrompt,
                          @Value("${chat.system-prompt:classpath:prompts/system.md}") String promptName,
                          @Value("${eval.limit:1000}") int limit,
                          // --eval.judge=false: solo responde y guarda lo que encontró la búsqueda, sin pagar al juez.
                          @Value("${eval.judge:true}") boolean judge) throws IOException {
        this.chatService = chatService;
        this.claude = claude;
        this.json = json;
        this.model = model;
        this.judgeModel = judgeModel;
        this.judgePrompt = judgePrompt.getContentAsString(StandardCharsets.UTF_8);
        this.promptName = promptName;
        this.limit = limit;
        this.judge = judge;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!List.of(args).contains("quality")) {
            return;
        }
        List<QualityQuestion> questions = json.readValue(Path.of("eval", "quality.json").toFile(),
                new TypeReference<List<QualityQuestion>>() {});

        // Varias a la vez: cada pregunta (con su conversación previa) es independiente de las demás.
        List<Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Result>> pending = questions.stream().limit(limit)
                    .map(q -> pool.submit(() -> evaluate(q))).toList();
            for (Future<Result> f : pending) {
                Result r = f.get();
                results.add(r);
                Review v = r.review();
                System.out.printf("%2d/10  responde %d/5  %s%s%s%s  %-22s %s%n", v.score(), v.answers(),
                        v.formatOk() ? "   " : "fmt", v.offTopic().isEmpty() ? "   " : "ot" + v.offTopic().size(),
                        v.needlessCaveats().isEmpty() ? "   " : "av" + v.needlessCaveats().size(),
                        v.unsupported().isEmpty() ? "   " : "sr" + v.unsupported().size(),
                        r.question().id(), v.comment());
            }
        }
        printSummary(results);

        Path out = Path.of("eval", "results",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-" + model + "-quality.json");
        Files.createDirectories(out.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), results);
        System.out.println("Detalle guardado en " + out);
    }

    private Result evaluate(QualityQuestion q) {
        // Primero se responde la conversación previa, como lo haría una persona en la web.
        List<Turn> history = new ArrayList<>();
        StringBuilder previous = new StringBuilder();
        for (String p : q.previous() == null ? List.<String>of() : q.previous()) {
            ChatAnswer a = chatService.answer(history, p, ChatListener.NONE);
            history.add(new Turn("user", p));
            history.add(new Turn("assistant", a.text()));
            previous.append("- ").append(p).append("\n");
        }

        long start = System.currentTimeMillis();
        ChatAnswer answer = chatService.answer(history, q.question(), ChatListener.NONE);
        long millis = System.currentTimeMillis() - start;

        StringBuilder sources = new StringBuilder();
        answer.citedSources().forEach((n, s) ->
                sources.append("[").append(n).append("] ").append(s.title()).append("\n").append(s.content()).append("\n\n"));
        String input = "CONVERSACIÓN PREVIA:\n" + (previous.isEmpty() ? "(ninguna)\n" : previous)
                + "\nPREGUNTA:\n" + q.question()
                + "\n\nQUÉ SE ESPERA:\n" + q.expect()
                + "\n\nRESPUESTA:\n<answer>\n" + answer.text() + "\n</answer>"
                + "\n\nFRAGMENTOS CITADOS:\n" + (sources.isEmpty() ? "(ninguno)" : sources);

        List<Seen> seen = java.util.stream.IntStream.range(0, answer.seen().size())
                .mapToObj(i -> new Seen(i + 1, answer.seen().get(i).slug(), answer.seen().get(i).content())).toList();
        Review review = judge ? judge(input) : new Review(0, List.of(), List.of(), true, "", List.of(), 0, "sin juez");
        return new Result(q, answer.text(), review, answer.queries(), seen, answer.inputTokens(), answer.outputTokens(), millis);
    }

    private Review judge(String input) {
        var response = claude.messages().create(MessageCreateParams.builder()
                .model(judgeModel)
                .maxTokens(4000L)
                .system(judgePrompt)
                .outputConfig(Review.class)
                .addUserMessage(input)
                .build());
        synchronized (this) {
            judgeInput += response.usage().inputTokens();
            judgeOutput += response.usage().outputTokens();
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El juez no devolvió resultado"));
    }

    private void printSummary(List<Result> results) {
        double score = results.stream().mapToInt(r -> r.review().score()).average().orElse(0);
        double answers = results.stream().mapToInt(r -> r.review().answers()).average().orElse(0);
        long formatOk = results.stream().filter(r -> r.review().formatOk()).count();
        int offTopic = results.stream().mapToInt(r -> r.review().offTopic().size()).sum();
        int caveats = results.stream().mapToInt(r -> r.review().needlessCaveats().size()).sum();
        int unsupported = results.stream().mapToInt(r -> r.review().unsupported().size()).sum();
        double words = results.stream().mapToInt(r -> r.answer().split("\\s+").length).average().orElse(0);
        long withList = results.stream().filter(r -> r.answer().lines().anyMatch(l -> l.matches("\\s*(- |\\d+\\. ).*"))).count();

        // Precios por millón de tokens: Haiku 5.5 $0,10/$0,50 y Sonnet 5.5 $2/$10.
        boolean sonnet = model.contains("sonnet");
        double answerCost = results.stream().mapToDouble(r -> sonnet
                ? r.inputTokens() * 2.0 + r.outputTokens() * 10.0
                : r.inputTokens() * 0.10 + r.outputTokens() * 0.50).sum() / 1_000_000;
        double judgeCost = (judgeInput * 2.0 + judgeOutput * 10.0) / 1_000_000;

        System.out.printf("%n=== %s · %s · %d preguntas%n", model, promptName, results.size());
        System.out.printf("Nota media: %.1f/10 · responde a lo pedido: %.1f/5 · formato bien: %d/%d%n",
                score, answers, formatOk, results.size());
        System.out.printf("Partes que sobran: %d · avisos innecesarios: %d · atribuido sin respaldo: %d%n",
                offTopic, caveats, unsupported);
        System.out.printf("Palabras de media: %.0f · respuestas con lista: %d/%d%n", words, withList, results.size());
        System.out.printf("Coste -> respuestas (solo la última de cada conversación): $%.4f · juez: $%.4f%n%n",
                answerCost, judgeCost);
    }
}
