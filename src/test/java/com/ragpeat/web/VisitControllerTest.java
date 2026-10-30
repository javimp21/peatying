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
        assertThat(VisitController.origin(new VisitController.VisitRequest("", "reddit"))).isEqualTo("reddit");
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.reddit.com/", " Reddit "))).isEqualTo("reddit");
    }

    @Test
    void marcaRaraOVacia_seUsaElReferrer() {
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.google.com/", "<script>"))).isEqualTo("google.com");
        assertThat(VisitController.origin(new VisitController.VisitRequest("https://www.google.com/", "x".repeat(31)))).isEqualTo("google.com");
        assertThat(VisitController.origin(new VisitController.VisitRequest("", null))).isNull();
        assertThat(VisitController.origin(null)).isNull();
    }
}
