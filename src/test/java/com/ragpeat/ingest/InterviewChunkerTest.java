package com.ragpeat.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.ragpeat.ingest.Interview.Section;
import com.ragpeat.ingest.Interview.Turn;
import com.ragpeat.ingest.InterviewFileStore.Episode;
import com.ragpeat.ingest.InterviewFileStore.EpisodeTurn;
import org.junit.jupiter.api.Test;

class InterviewChunkerTest {

    private final InterviewChunker chunker = new InterviewChunker();

    @Test
    void convierteElEpisodio_conNombresDeHablanteYTitulo() {
        Episode episode = new Episode("kmud-081201-cholesterol", "Ask the Herb Doctor (KMUD)", "2008-12-01",
                "Cholesterol is an important molecule", "https://example.com/a.mp3", List.of(
                new EpisodeTurn("HOST", 0, "What about cholesterol?"),
                new EpisodeTurn("PEAT", 12.5, " It is protective. ")));

        Interview interview = InterviewFileStore.toInterview(episode);

        assertThat(interview.slug()).isEqualTo("interview-kmud-081201-cholesterol");
        assertThat(interview.title()).isEqualTo("Ask the Herb Doctor (KMUD): Cholesterol is an important molecule (2008)");
        assertThat(interview.url()).isEqualTo("https://example.com/a.mp3");
        List<Turn> turns = interview.sections().getFirst().turns();
        assertThat(turns.get(0).speaker()).isEqualTo("Host");
        assertThat(turns.get(1).isPeat()).isTrue();
        assertThat(turns.get(1).text()).isEqualTo("It is protective.");
    }

    @Test
    void descartaLosTramosDondePeatApenasHabla() {
        Interview interview = interview(
                new Turn("Host", "Welcome, everyone, to tonight's show about thyroid and metabolism."),
                new Turn(Interview.PEAT, "Thank you."));

        assertThat(chunker.split(interview)).isEmpty();
    }

    @Test
    void unaRespuestaLargaSigueEnOtroChunk_conElIntercambioAnteriorComoContexto() {
        String answer = "The thyroid regulates energy production in every cell of the body. ".repeat(40);
        Interview interview = interview(
                new Turn("Host", "Why is the thyroid so important for the whole body?"),
                new Turn(Interview.PEAT, answer.strip()));

        List<String> chunks = chunker.split(interview);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(
                Chunker.MAX_CHARS + 2 * InterviewChunker.MAX_CONTEXT_CHARS + 100));
        // Peat aún no había hablado: el contexto es solo la pregunta.
        assertThat(chunks.get(0)).startsWith(InterviewChunker.CONTEXT_HEADER
                + "\nHost: Why is the thyroid so important for the whole body?\n\nRay Peat: The thyroid");
        // El corte cae en mitad del turno de Peat: el contexto lleva la pregunta y el principio de ese mismo turno.
        assertThat(chunks.get(1)).startsWith(InterviewChunker.CONTEXT_HEADER + "\n"
                + "Host: Why is the thyroid so important for the whole body?\nRay Peat: The thyroid regulates")
                .contains("…\n\nRay Peat: The thyroid");
    }

    @Test
    void unaPreguntaNuevaLlevaLaPreguntaYLaRespuestaAnteriores_quePuedenNombrarElTema() {
        String firstAnswer = "If the pregnenolone is well-made and pure, it keeps the stress hormones low. "
                + "Energy and warmth come first in his view of metabolism. ".repeat(25);
        Interview interview = interview(
                new Turn("Host", "What happens with the Pregnown Law in people?"),
                new Turn(Interview.PEAT, firstAnswer.substring(0, 1380).strip()),
                new Turn("Host", "So we could presume that would happen for people too? Would it bring stress hormones down in people too?"),
                new Turn(Interview.PEAT, "I decided to take 3,000 milligrams to 4,000 milligrams every day for a year. ".repeat(6).strip()));

        List<String> chunks = chunker.split(interview);

        assertThat(chunks).hasSize(2);
        String second = chunks.get(1);
        assertThat(second).startsWith(InterviewChunker.CONTEXT_HEADER + "\nHost: What happens with the Pregnown Law in people?\n"
                + "Ray Peat: If the pregnenolone is well-made and pure");
        // Sin el contexto, el chunk no nombra el tema.
        String body = second.substring(second.indexOf("\n\nHost: So we could"));
        assertThat(body).doesNotContain("pregnenolone");
        assertThat(second).contains("pregnenolone");
    }

    @Test
    void unRellenoComoWowNoSustituyeALaPregunta() {
        Interview interview = interview(
                new Turn("Host", "How much pregnenolone did you take every day during that year?"),
                new Turn(Interview.PEAT, "I took three grams of it. ".repeat(50).strip()),
                new Turn("Host", "Wow."),
                new Turn(Interview.PEAT, "It did not feel any different to me than a small dose. ".repeat(40).strip()));

        List<String> chunks = chunker.split(interview);

        assertThat(chunks).hasSizeGreaterThan(1);
        String last = chunks.getLast();
        assertThat(last).startsWith(InterviewChunker.CONTEXT_HEADER + "\nHost: How much pregnenolone did you take every day during that year?\n"
                + "Ray Peat: It did not feel any different");
        assertThat(last.substring(0, last.indexOf("\n\n"))).doesNotContain("Wow.");
    }

    @Test
    void elContextoNoCuentaComoLoQueDicePeat() {
        Interview interview = interview(
                new Turn("Host", "How much pregnenolone did you take every day during that year?"),
                new Turn(Interview.PEAT, "I took three grams of it and felt fine. ".repeat(35).strip()),
                new Turn("Host", "Thank you for that answer, and now a long announcement about the next show and its guests. ".repeat(8).strip()));

        List<String> chunks = chunker.split(interview);

        // El segundo chunk solo tendría al presentador más el principio de la respuesta de Peat que va de contexto.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).doesNotContain(InterviewChunker.CONTEXT_HEADER);
    }

    @Test
    void elPrimerChunkDeCadaSeccionNoLlevaContexto() {
        String answer = "The thyroid regulates energy production in every cell of the body. ".repeat(40);
        Interview interview = new Interview("interview-test", "Test", "url", List.of(
                new Section("One", List.of(new Turn("Host", "Why is the thyroid so important for the whole body?"),
                        new Turn(Interview.PEAT, answer.strip()))),
                new Section("Two", List.of(new Turn("Host", "And what about progesterone in the same context?"),
                        new Turn(Interview.PEAT, "Progesterone protects the brain from excitation and stress. ".repeat(5).strip())))));

        List<String> chunks = chunker.split(interview);

        String sectionTwoFirst = chunks.getLast();
        assertThat(sectionTwoFirst).startsWith("Host: And what about progesterone");
        assertThat(sectionTwoFirst).doesNotContain(InterviewChunker.CONTEXT_HEADER);
    }

    @Test
    void elTextoQueVeElUsuarioNoLlevaElContexto() {
        String chunk = InterviewChunker.CONTEXT_HEADER + "\nHost: A question long enough to count?\nRay Peat: An answer…\n\nHost: Next?\n\nRay Peat: Reply.";

        assertThat(InterviewChunker.withoutContext(chunk)).isEqualTo("Host: Next?\n\nRay Peat: Reply.");
        assertThat(InterviewChunker.withoutContext("Host: Next?\n\nRay Peat: Reply.")).isEqualTo("Host: Next?\n\nRay Peat: Reply.");
        String withoutBlankLine = InterviewChunker.CONTEXT_HEADER + "\nHost: A question long enough to count?";
        assertThat(InterviewChunker.withoutContext(withoutBlankLine)).isEqualTo(withoutBlankLine);
    }

    private static Interview interview(Turn... turns) {
        return new Interview("interview-test", "Test", "url", List.of(new Section("", List.of(turns))));
    }
}
