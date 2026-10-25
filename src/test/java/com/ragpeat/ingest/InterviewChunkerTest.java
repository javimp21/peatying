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
    void unaRespuestaLargaSigueEnOtroChunk_conLaPreguntaComoContexto() {
        String answer = "The thyroid regulates energy production in every cell of the body. ".repeat(40);
        Interview interview = interview(
                new Turn("Host", "Why is the thyroid so important?"),
                new Turn(Interview.PEAT, answer.strip()));

        List<String> chunks = chunker.split(interview);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(
                Chunker.MAX_CHARS + InterviewChunker.MAX_CONTEXT_CHARS + 50));
        assertThat(chunks.get(0)).startsWith("Host: Why is the thyroid so important?\n\nRay Peat: The thyroid");
        // El segundo chunk repite la pregunta y vuelve a decir quién habla.
        assertThat(chunks.get(1)).startsWith("Host: Why is the thyroid so important?\n\nRay Peat: The thyroid");
    }

    private static Interview interview(Turn... turns) {
        return new Interview("interview-test", "Test", "url", List.of(new Section("", List.of(turns))));
    }
}
