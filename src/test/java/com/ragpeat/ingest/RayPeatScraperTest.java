package com.ragpeat.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * Prueba el extractor con HTML inventado que imita los dos formatos de raypeat.com.
 * No usa internet ni Spring: es un test unitario puro y tarda milisegundos.
 */
class RayPeatScraperTest {

    private final RayPeatScraper scraper = new RayPeatScraper();
    private static final String URL = "https://raypeat.com/articles/articles/ejemplo.shtml";

    @Test
    void siElArticuloEmpiezaConUnaCitaFirmada_usaElTitleDeLaPagina() {
        String html = """
                <html><head><title>Cancer: Disorder and Energy</title></head><body>
                <p>For every thing that lives is holy, life delights in life.... W. Blake</p>
                <p>Kurt Goldstein: "Life is a condition alternating between excitation and rest."</p>
                </body></html>
                """;

        Article article = scraper.parseArticle(URL, Jsoup.parse(html));

        assertThat(article.title()).isEqualTo("Cancer: Disorder and Energy");
    }

    @Test
    void formatoAntiguo_conParrafosP_yTituloReferences() {
        String html = """
                <html><head><title>New Page Title Here</title></head><body>
                <div class="line">
                  A R T I C L E<br>
                  <p><b>Gelatin, stress, longevity</b></p>
                  <p>=========</p>
                  <p>Primer párrafo
                  partido en varias líneas.</p>
                  <p>Segundo párrafo.</p>
                  <p><b>REFERENCES</b></p>
                  <p>Am J Physiol. 1990 Jul;259(1 Pt 2):F80-7. Algo.</p>
                </div></body></html>
                """;

        Article article = scraper.parseArticle(URL, Jsoup.parse(html));

        // El <title> de la página está mal: el título real se toma de la primera línea del texto.
        assertThat(article.title()).isEqualTo("Gelatin, stress, longevity");
        assertThat(article.text())
                .isEqualTo("Gelatin, stress, longevity\n\nPrimer párrafo partido en varias líneas.\n\nSegundo párrafo.");
    }

    @Test
    void cortaEnTitulosDeBibliografiaConOtrasPalabras_peroNoEnFrasesNormales() {
        String html = """
                <html><head><title>Estrogen</title></head><body>
                <div class="line">
                  <p>Este párrafo menciona unas references cualquiera.</p>
                  <p>NOTES AND REFERENCES</p>
                  <p>1. Una nota con un resumen largo que no parece una cita.</p>
                </div></body></html>
                """;

        Article article = scraper.parseArticle(URL, Jsoup.parse(html));

        assertThat(article.text()).isEqualTo("Este párrafo menciona unas references cualquiera.");
    }

    @Test
    void cambiaLosCaracteresRotosDeLaWebPorComillas() {
        String html = """
                <html><head><title>Rotos</title></head><body>
                <div class="line"><p>The industryï¿½s ï¿½inhibitoryï¿½ myth.</p></div>
                </body></html>
                """;

        Article article = scraper.parseArticle(URL, Jsoup.parse(html));

        // Entre letras era un apóstrofe; junto a espacios o puntuación, unas comillas.
        assertThat(article.text()).isEqualTo("The industry's \"inhibitory\" myth.");
    }

    @Test
    void formatoNuevo_conDivYBr_yCitasSinTitulo() {
        String html = """
                <html><head><title>Glucose</title></head><body>
                <div class="line">
                  <div>Primer párrafo (Barat, et al., 2008).</div>
                  <div><br>Segundo párrafo.</div>
                  Diabetes Metab. 2008 Oct 24;34(5):1-2. <b>Cita uno.</b><br>
                  Am J Physiol. 1990 Jul;259(1):F80-7. <b>Cita dos.</b><br>
                  &copy; Ray Peat Ph.D. 2012. All Rights Reserved.
                </div></body></html>
                """;

        Article article = scraper.parseArticle(URL, Jsoup.parse(html));

        assertThat(article.text()).isEqualTo("Primer párrafo (Barat, et al., 2008).\n\nSegundo párrafo.");
    }
}
