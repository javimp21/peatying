package com.ragpeat.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

/**
 * Sabe leer raypeat.com: la lista de artículos y cada artículo.
 * No guarda nada en disco; eso lo hace ArticleFileStore.
 */
@Component
public class RayPeatScraper {

    private static final String INDEX_URL = "https://raypeat.com/articles/";
    private static final String USER_AGENT = "rag-peat/0.1 (proyecto personal de aprendizaje)";

    /**
     * Una cita bibliográfica tiene un año seguido de volumen/páginas: "1990 Jul;259(1 Pt 2):F80-7".
     * El texto normal casi nunca tiene ese patrón (escribe "(Barat, et al., 2008)").
     */
    /**
     * Una cita con su autor al final: "For every thing that lives is holy... W. Blake".
     * Algunos artículos empiezan así (un epígrafe), y esa primera línea NO es el título.
     */
    private static final Pattern EPIGRAPH = Pattern.compile("(\\.{3,}|…|[\"”])\\s*[-–—]?\\s*(\\p{Lu}\\.\\s*)*\\p{Lu}[\\p{L}.' ]{1,30}$");
    private static final Pattern CITATION = Pattern.compile("\\b(18|19|20)\\d{2}\\b.{0,30}[;:(]\\s*\\d");

    /**
     * Artículos que no queremos, comprobado a mano:
     *  - Traducciones (español, italiano): duplican artículos que ya están en inglés, y la del español
     *    "ganaba" todas las búsquedas solo por estar en el mismo idioma que la pregunta.
     *  - Duplicados: el de esclerosis múltiple es idéntico a "multiple-sclerosis", y el de CO2 es
     *    otra versión casi igual de "co2". Repetidos, ocuparían huecos en los resultados con lo mismo.
     */
    private static final Set<String> SKIPPED = Set.of(
            "spanish-alzheimers", "italian-salt",
            "multiple-sclerosis-hormone-related-brain-syndromes", "protective-co2-aging");

    /** Devuelve las URLs de todos los artículos HTML del índice (sin repetidos). */
    public List<String> fetchArticleUrls() throws IOException {
        Document index = download(INDEX_URL);

        // LinkedHashSet: quita duplicados y mantiene el orden en que aparecen.
        Set<String> urls = new LinkedHashSet<>();
        // Selector CSS: enlaces <a> cuyo href acaba en ".shtml" y está dentro de /articles/articles/.
        for (Element link : index.select("a[href*=/articles/articles/][href$=.shtml]")) {
            // "abs:href" convierte "/articles/..." en "https://raypeat.com/articles/...".
            String url = link.attr("abs:href");
            if (!SKIPPED.contains(new Article(url, "", "").slug())) {
                urls.add(url);
            }
        }
        return new ArrayList<>(urls);
    }

    /** Descarga un artículo y se queda solo con su título y sus párrafos de texto. */
    public Article fetchArticle(String url) throws IOException {
        return parseArticle(url, download(url));
    }

    /**
     * Separado de fetchArticle para poder probarlo con HTML de ejemplo, sin internet.
     * (Sin "public": solo lo usan esta clase y los tests del mismo paquete.)
     */
    Article parseArticle(String url, Document page) {
        String title = clean(page.title());

        // El cuerpo del artículo está en <div class="line">.
        Element body = page.selectFirst("div.line");
        if (body == null) {
            return new Article(url, title, "");
        }

        String[] lines = textWithLineBreaks(body).split("\n");
        List<String> paragraphs = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String text = clean(lines[i]);
            String next = i + 1 < lines.length ? clean(lines[i + 1]) : "";

            // Lo que va después es bibliografía: para el chatbot es ruido.
            // Unos artículos tienen un título corto en mayúsculas ("REFERENCES", "NOTES AND REFERENCES",
            // "OTHER REFERENCES"...); otros empiezan directamente con las citas
            // (por eso pedimos dos citas seguidas, para no cortar por una frase suelta).
            if (isReferencesHeading(text)
                    || (CITATION.matcher(text).find() && CITATION.matcher(next).find())) {
                break;
            }
            if (!isNoise(text)) {
                paragraphs.add(text);
            }
        }

        // La etiqueta <title> de la web a veces está mal ("New Page Title Here", o el título de otro
        // artículo). El título real suele ser la primera línea del texto; si es corta, usamos esa.
        // Salvo que esa primera línea sea un epígrafe (una cita firmada): entonces vale el <title>.
        if (!paragraphs.isEmpty() && paragraphs.getFirst().length() <= 200
                && !EPIGRAPH.matcher(paragraphs.getFirst()).find()) {
            title = paragraphs.getFirst();
        }

        return new Article(url, title, String.join("\n\n", paragraphs));
    }

    /**
     * Convierte el HTML en texto conservando dónde acaba cada párrafo.
     * La web usa dos formatos: párrafos <p> (artículos antiguos) y <div> con <br> (los nuevos).
     * Recorremos todos los nodos: el texto se copia tal cual, y cada <br> o bloque (<p>, <div>...)
     * se convierte en un salto de línea.
     */
    private String textWithLineBreaks(Element body) {
        StringBuilder sb = new StringBuilder();
        body.traverse(new NodeVisitor() {
            // head(): al ENTRAR en un nodo.
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    sb.append(textNode.text());
                } else if (node instanceof Element element
                        && (element.normalName().equals("br") || element.isBlock())) {
                    sb.append("\n");
                }
            }

            // tail(): al SALIR de un nodo. Si no cortamos al cerrar un bloque, el texto
            // que va justo después de "</div>" se pega al párrafo anterior.
            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && element.isBlock()) {
                    sb.append("\n");
                }
            }
        });
        return sb.toString();
    }

    private String clean(String line) {
        return line
                //   es el espacio "duro" (&nbsp;); trim() no lo quita, así que lo cambiamos antes.
                .replace(' ', ' ')
                // Algunas páginas ya vienen rotas desde la web: el carácter original se perdió y quedó "ï¿½".
                // Entre dos letras era un apóstrofe (industry's); en otro sitio, unas comillas ("inhibitory").
                .replaceAll("(?<=\\p{L})ï¿½(?=\\p{L})", "'")
                .replace("ï¿½", "\"")
                .trim();
    }

    /**
     * Un título de bibliografía es una línea corta con "REFERENCES" en mayúsculas.
     * Exigimos mayúsculas y línea corta para no cortar en una frase normal que diga "references".
     */
    private boolean isReferencesHeading(String text) {
        return text.length() < 40 && text.contains("REFERENCES");
    }

    /** Líneas que no son contenido: vacías, decoración, cabecera y pie de la web. */
    private boolean isNoise(String text) {
        return text.isEmpty()
                || text.matches("=+")              // "========"
                || text.startsWith("©")            // "© Ray Peat Ph.D. 2012. All Rights Reserved."
                || text.equals("A R T I C L E")
                || text.contains("^^^");           // enlace "^^^ Top ^^^"
    }

    private Document download(String url) throws IOException {
        Connection.Response response = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(30_000)
                .execute();
        // La web dice ser ISO-8859-1, pero en realidad usa Windows-1252 (comillas “ ”).
        // Si no lo forzamos, esas comillas salen como caracteres raros.
        response.charset("windows-1252");
        return response.parse();
    }
}
