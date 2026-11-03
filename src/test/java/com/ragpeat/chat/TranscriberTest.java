package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TranscriberTest {

    @Test
    void reconoceLosFormatosQueGrabanLosNavegadores_porSusPrimerosBytes() {
        byte[] webm = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};                 // Chrome, Firefox, Android
        byte[] mp4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};             // Safari
        byte[] ogg = "OggS\0\0\0\0".getBytes(StandardCharsets.US_ASCII);

        assertThat(Transcriber.detect(webm)).contains(Transcriber.Kind.WEBM);
        assertThat(Transcriber.detect(mp4)).contains(Transcriber.Kind.MP4);
        assertThat(Transcriber.detect(ogg)).contains(Transcriber.Kind.OGG);
    }

    @Test
    void corrigeElNombreDePeatMalEntendido_sinTocarOtrasPalabras() {
        assertThat(Transcriber.fixName("What did Ray Pede think about thyroid?")).isEqualTo("What did Ray Peat think about thyroid?");
        assertThat(Transcriber.fixName("¿Qué decía ray pete del café?")).isEqualTo("¿Qué decía Ray Peat del café?");
        for (String heard : new String[] {"Ray Pid", "Ray Pit", "Ray Pitt", "Ray Pied", "Ray Peed", "Ray Pede", "Ray Peat"}) {
            assertThat(Transcriber.fixName("¿Qué opinaba " + heard + " del azúcar?")).isEqualTo("¿Qué opinaba Ray Peat del azúcar?");
        }
        assertThat(Transcriber.fixName("Mi médico pide análisis y el rey pide paz")).isEqualTo("Mi médico pide análisis y el rey pide paz");
        assertThat(Transcriber.fixName("Ray Peterson and a ray of light")).isEqualTo("Ray Peterson and a ray of light");
    }

    @Test
    void rechazaLoQueNoEsAudioGrabado() {
        assertThat(Transcriber.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(Transcriber.detect(new byte[] {1, 2})).isEmpty();
    }
}
