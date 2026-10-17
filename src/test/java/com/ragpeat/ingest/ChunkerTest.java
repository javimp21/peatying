package com.ragpeat.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkerTest {

    private final Chunker chunker = new Chunker();

    @Test
    void parrafosCortos_seJuntanEnUnSoloChunk() {
        List<String> chunks = chunker.split("Uno.\n\nDos.\n\nTres.");

        assertThat(chunks).containsExactly("Uno.\n\nDos.\n\nTres.");
    }

    @Test
    void cuandoSePasaDelMaximo_empiezaUnChunkNuevo_sinPartirParrafos() {
        String a = "a".repeat(1000);
        String b = "b".repeat(1000);

        List<String> chunks = chunker.split(a + "\n\n" + b);

        assertThat(chunks).containsExactly(a, b);
    }

    @Test
    void unParrafoDemasiadoLargo_sePartePorFrases() {
        String sentence = "x".repeat(599) + "."; // 600 caracteres
        String longParagraph = String.join(" ", sentence, sentence, sentence, sentence); // ~2400

        List<String> chunks = chunker.split(longParagraph);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(Chunker.MAX_CHARS));
        assertThat(String.join(" ", chunks)).isEqualTo(longParagraph); // no se pierde ni una letra
    }
}
