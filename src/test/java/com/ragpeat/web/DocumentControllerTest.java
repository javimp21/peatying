package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import com.ragpeat.chat.DocumentReader;
import com.ragpeat.chat.DocumentReader.Kind;
import org.junit.jupiter.api.Test;

class DocumentControllerTest {

    @Test
    void recognisesFilesByTheirContent() {
        assertThat(DocumentReader.detect("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).contains(Kind.PDF);
        assertThat(DocumentReader.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00})).contains(Kind.JPEG);
        assertThat(DocumentReader.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D})).contains(Kind.PNG);
        assertThat(DocumentReader.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).contains(Kind.WEBP);
    }

    @Test
    void rejectsAnythingElseWhateverItsName() {
        assertThat(DocumentReader.detect("<html><script>".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(DocumentReader.detect("MZ executable".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(DocumentReader.detect(new byte[0])).isEmpty();
    }

    @Test
    void cleansTheDisplayName() {
        assertThat(DocumentController.displayName("C:\\Users\\ana\\analítica 2026.pdf")).isEqualTo("analítica 2026.pdf");
        assertThat(DocumentController.displayName("<img src=x onerror=alert(1)>.png")).isEqualTo("img srcx onerroralert(1).png");
        assertThat(DocumentController.displayName(null)).isEqualTo("documento");
        assertThat(DocumentController.displayName("a".repeat(100) + ".pdf")).hasSize(58);
    }
}
