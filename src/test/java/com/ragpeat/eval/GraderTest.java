package com.ragpeat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.TreeMap;

import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.ingest.Article;
import com.ragpeat.ingest.Interview;
import com.ragpeat.ingest.Interview.Section;
import com.ragpeat.ingest.Interview.Turn;
import com.ragpeat.search.SearchResult;
import org.junit.jupiter.api.Test;

class GraderTest {

    private final Grader grader = new Grader(List.of(
            new Article("https://raypeat.com/articles/articles/gelatin.shtml", "Gelatin",
                    "Glycine is recognized as an \"inhibitory\" neurotransmitter, and it’s very safe.")),
            List.of(new Interview("interview-x", "Show", "url", List.of(new Section("Gelatin", List.of(
                    new Turn("Host", "Do you think gelatin makes everyone live forever?"),
                    new Turn(Interview.PEAT, "Gelatin is a very good balancing protein for most people.")))))));

    private ChatAnswer answer(String text, String... citedSlugs) {
        TreeMap<Integer, SearchResult> cited = new TreeMap<>();
        for (int i = 0; i < citedSlugs.length; i++) {
            cited.put(i + 1, new SearchResult(i, citedSlugs[i], "t", "https://raypeat.com/articles/articles/" + citedSlugs[i] + ".shtml", "article", "c", 0));
        }
        return new ChatAnswer(text, cited, 1, 0, 0);
    }

    @Test
    void apruebaSiCitaElArticuloEsperado_yLaCitaLiteralExiste() {
        var q = new EvalQuestion("g", "direct", "?", List.of("gelatin"));

        // En el original "inhibitory" va entre comillas; el bot las quita. Debe contar como cita válida.
        var grade = grader.grade(q, answer("Dice que \"Glycine is recognized as an inhibitory\" [1].", "gelatin"));

        assertThat(grade.sourceHit()).isTrue();
        assertThat(grade.passed()).isTrue();
    }

    @Test
    void detectaUnaCitaInventada() {
        var q = new EvalQuestion("g", "direct", "?", List.of("gelatin"));

        var grade = grader.grade(q, answer("Peat dice \"gelatin cures every disease known\" [1].", "gelatin"));

        assertThat(grade.passed()).isFalse();
        assertThat(grade.invalidQuotes()).containsExactly("gelatin cures every disease known");
    }

    @Test
    void distingueCitasDeEntrevistas_yNoCuentaLoQueDiceElPresentador() {
        var q = new EvalQuestion("g", "direct", "?", List.of("gelatin"));

        var fromPeat = grader.grade(q, answer("Dijo \"a very good balancing protein for most people\" [1].", "gelatin"));
        var fromHost = grader.grade(q, answer("Dijo \"gelatin makes everyone live forever\" [1].", "gelatin"));

        assertThat(fromPeat.passed()).isFalse();
        assertThat(fromPeat.interviewQuotes()).hasSize(1);
        assertThat(fromPeat.invalidQuotes()).isEmpty();
        // Lo dijo el presentador, no Peat: atribuírselo a Peat es inventar.
        assertThat(fromHost.invalidQuotes()).hasSize(1);
    }

    @Test
    void aceptaApostrofesTipograficos() {
        var q = new EvalQuestion("g", "direct", "?", List.of("gelatin"));

        var grade = grader.grade(q, answer("Según él, “and it's very safe” [1].", "gelatin"));

        assertThat(grade.passed()).isTrue();
    }

    @Test
    void ignoraFrasesEnEspanolEntreComillas_yNoJuntaDosCitasDistintas() {
        var q = new EvalQuestion("g", "direct", "?", List.of("gelatin"));

        var grade = grader.grade(q, answer(
                "La llamaba \"la hormona de la felicidad\" [1]. Dice \"Glycine is recognized\" y luego [1] \"and it's very safe\".",
                "gelatin"));

        assertThat(grade.quotes()).isEqualTo(2);
        assertThat(grade.passed()).isTrue();
    }

    @Test
    void tambienCompruebaCitasEnInglesEntreComillasAngulares() {
        var q = new EvalQuestion("g", "direct", "?", List.of("gelatin"));

        var grade = grader.grade(q, answer(
                "Lo llamaba «gelatin cures every disease known» [1], no «hormona del embarazo» [1].", "gelatin"));

        // La inglesa se comprueba (y es inventada); la española sin tildes se ignora.
        assertThat(grade.invalidQuotes()).containsExactly("gelatin cures every disease known");
    }

    @Test
    void absent_apruebaSiReconoceQueNoLoMenciona() {
        var q = new EvalQuestion("k", "absent", "?", List.of());

        assertThat(grader.grade(q, answer("En estos artículos Peat no menciona la kombucha.")).passed()).isTrue();
        assertThat(grader.grade(q, answer("La kombucha es excelente para la tiroides.")).passed()).isFalse();
    }

    @Test
    void offTopic_suspendeSiCitaFuentes() {
        var q = new EvalQuestion("m", "off_topic", "?", List.of());

        assertThat(grader.grade(q, answer("Solo hablo de Ray Peat.")).passed()).isTrue();
        assertThat(grader.grade(q, answer("Ganó España [1].", "gelatin")).passed()).isFalse();
    }
}
