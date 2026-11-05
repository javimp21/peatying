package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VisitControllerTest {

    @Test
    void guardaSoloElDominio_sinLaDireccionCompleta() {
        assertThat(VisitController.referrerHost("https://www.google.com/search?q=ray+peat+azucar")).isEqualTo("google.com");
        assertThat(VisitController.referrerHost("https://t.co/AbC123")).isEqualTo("t.co");
        assertThat(VisitController.referrerHost("https://L.Instagram.com/?u=x")).isEqualTo("l.instagram.com");
    }

    @Test
    void directoOInvalidoONuestraPropiaWeb_esNull() {
        assertThat(VisitController.referrerHost(null)).isNull();
        assertThat(VisitController.referrerHost("")).isNull();
        assertThat(VisitController.referrerHost("no es una url")).isNull();
        assertThat(VisitController.referrerHost("https://peatying.com/about")).isNull();
        assertThat(VisitController.referrerHost("http://localhost:8080/")).isNull();
    }

    @Test
    void laMarcaDelEnlace_mandaSobreElReferrer() {
        assertThat(VisitController.origin(new VisitController.VisitRequest("", "reddit", null))).isEqualTo("reddit");
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.reddit.com/", " Reddit ", null))).isEqualTo("reddit");
    }

    @Test
    void marcaRaraOVacia_seUsaElReferrer() {
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.google.com/", "<script>", "<script>"))).isEqualTo("google.com");
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.google.com/", "x".repeat(31), "x".repeat(31)))).isEqualTo("google.com");
        assertThat(VisitController.origin(new VisitController.VisitRequest("", null, null))).isNull();
        assertThat(VisitController.origin(null)).isNull();
    }

    @Test
    void laMarcaDeChatGpt_seVeAunqueLaAppNoDigaElOrigen_peroLaNuestraManda() {
        assertThat(VisitController.origin(new VisitController.VisitRequest("", null, "chatgpt.com"))).isEqualTo("chatgpt.com");
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.google.com/", null, " ChatGPT.com "))).isEqualTo("chatgpt.com");
        assertThat(VisitController.origin(new VisitController.VisitRequest("", "reddit", "chatgpt.com"))).isEqualTo("reddit");
    }
}
