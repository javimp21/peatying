package com.ragpeat.topics;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragpeat.topics.TopicsCommand.Faq;
import com.ragpeat.topics.TopicsCommand.Page;
import com.ragpeat.topics.TopicsCommand.Section;
import com.ragpeat.topics.TopicsCommand.Source;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el contenido de cada tema en una página HTML fija (static/temas/<slug>.html), más el índice
 * (static/temas/index.html) y el sitemap.xml con todas las páginas. Sin JavaScript: así cualquier buscador
 * o asistente de IA puede leerla entera.
 */
@Component
public class TopicPageRenderer {

    static final Path OUT = Path.of("src", "main", "resources", "static", "temas");
    static final Path SITEMAP = Path.of("src", "main", "resources", "static", "sitemap.xml");
    static final String SITE = "https://peatying.com";

    private final JsonMapper json;

    public TopicPageRenderer(JsonMapper json) {
        this.json = json;
    }

    public void writeAll(List<Page> pages) throws IOException {
        Files.createDirectories(OUT);
        for (Page page : pages) {
            Files.writeString(OUT.resolve(page.slug() + ".html"), page(page));
        }
        Files.writeString(OUT.resolve("index.html"), index(pages));
        Files.writeString(SITEMAP, sitemap(pages));
    }

    // --- Una página de tema ----------------------------------------------------------

    String page(Page p) {
        String url = SITE + "/temas/" + p.slug();
        // Corto a propósito (Bing avisa a partir de ~70 caracteres): "Ray Peat y el azúcar · Ray Peat Chatbot".
        String title = p.title();
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"topic-crumbs\"><a href=\"/temas\">Temas</a> · Ray Peat</p>\n");
        body.append("<h1>").append(esc(p.title())).append("</h1>\n");
        body.append("<p class=\"topic-intro\">").append(cites(p.intro())).append("</p>\n");
        body.append(askBox(p.question()));
        for (Section s : p.sections()) {
            body.append("<h2>").append(esc(s.heading())).append("</h2>\n");
            for (String paragraph : s.paragraphs()) {
                body.append("<p>").append(cites(paragraph)).append("</p>\n");
            }
        }
        body.append("<h2>Preguntas frecuentes</h2>\n<dl class=\"topic-faq\">\n");
        for (Faq f : p.faq()) {
            body.append("<dt>").append(esc(f.q())).append("</dt>\n<dd>").append(cites(f.a())).append("</dd>\n");
        }
        body.append("</dl>\n");
        body.append(askBox(p.question()));
        body.append("<h2>Fuentes</h2>\n<ol class=\"topic-sources\">\n");
        for (int i = 0; i < p.sources().size(); i++) {
            Source s = p.sources().get(i);
            body.append("<li id=\"fuente-").append(i + 1).append("\"><a href=\"").append(esc(s.url()))
                    // Algunos títulos de raypeat.com acaban en ":" ("Regeneration and degeneration:"): fuera.
                    .append("\" target=\"_blank\" rel=\"noopener\">").append(esc(s.title().replaceAll("\\s*:\\s*$", "")))
                    .append("</a> <span>")
                    .append("interview".equals(s.type()) ? "entrevista (transcripción automática)" : "artículo")
                    .append("</span></li>\n");
        }
        body.append("</ol>\n");
        body.append("<p class=\"topic-note\">Resumen hecho con IA a partir de los artículos y entrevistas de Ray Peat que se citan. "
                + "Puede contener errores y no es consejo médico. Proyecto independiente, sin relación con Ray Peat ni con raypeat.com.</p>\n");

        // Datos estructurados (schema.org) para Google y los asistentes de IA: un artículo y sus preguntas frecuentes.
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("@context", "https://schema.org");
        article.put("@type", "Article");
        article.put("headline", p.title());
        article.put("description", p.description());
        article.put("inLanguage", "es");
        article.put("url", url);
        article.put("about", Map.of("@type", "Person", "name", "Ray Peat"));
        article.put("publisher", Map.of("@type", "Organization", "name", "Ray Peat Chatbot", "url", SITE + "/"));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Faq f : p.faq()) {
            questions.add(Map.of("@type", "Question", "name", f.q(),
                    "acceptedAnswer", Map.of("@type", "Answer", "text", plain(f.a()))));
        }
        Map<String, Object> faqPage = Map.of("@context", "https://schema.org", "@type", "FAQPage", "mainEntity", questions);

        return document(title, p.description(), url, json.writeValueAsString(List.of(article, faqPage)), body.toString());
    }

    /** El botón que lleva al chat con la pregunta ya escrita (no se envía sola: la persona decide). */
    private String askBox(String question) {
        String href = "/?q=" + URLEncoder.encode(question, StandardCharsets.UTF_8);
        return "<p class=\"topic-ask\"><a href=\"" + esc(href) + "\">Pregúntale al chatbot: «" + esc(question) + "» →</a></p>\n";
    }

    // --- Índice de temas ----------------------------------------------------------------

    String index(List<Page> pages) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>Temas</h1>\n<p class=\"topic-intro\">Lo que pensaba Ray Peat sobre cada tema, resumido en español "
                + "y con enlaces a sus artículos y entrevistas originales. Para preguntar lo que quieras, usa el "
                + "<a href=\"/\">chatbot</a>.</p>\n<ul class=\"topic-index\">\n");
        for (Page p : pages) {
            body.append("<li><a href=\"/temas/").append(p.slug()).append("\">").append(esc(p.title()))
                    .append("</a> <span>").append(esc(p.description())).append("</span></li>\n");
        }
        body.append("</ul>\n");
        return document("Temas: las ideas de Ray Peat en español", "Las ideas de Ray Peat sobre el azúcar, la tiroides, "
                + "el estrés, los aceites vegetales y más, resumidas en español con sus fuentes.", SITE + "/temas", null, body.toString());
    }

    // --- Sitemap ------------------------------------------------------------------------

    String sitemap(List<Page> pages) {
        String today = LocalDate.now().toString();
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- Lo genera TopicsCommand (comando "topics"): las páginas que queremos que aparezcan en Google. -->
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        List<String> urls = new ArrayList<>(List.of("/", "/about", "/temas", "/privacidad"));
        pages.forEach(p -> urls.add("/temas/" + p.slug()));
        for (String path : urls) {
            xml.append("  <url><loc>").append(SITE).append(path).append("</loc><lastmod>").append(today).append("</lastmod></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    // --- Piezas comunes -------------------------------------------------------------------

    private String document(String title, String description, String url, String jsonLd, String body) {
        String structured = jsonLd == null ? ""
                : "  <script type=\"application/ld+json\">" + jsonLd.replace("</", "<\\/") + "</script>\n";
        return """
                <!doctype html>
                <html lang="es">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%1$s · Ray Peat Chatbot</title>
                  <meta name="description" content="%2$s">
                  <meta name="color-scheme" content="light">
                  <link rel="canonical" href="%3$s">
                  <meta property="og:type" content="article">
                  <meta property="og:site_name" content="Ray Peat Chatbot">
                  <meta property="og:title" content="%1$s">
                  <meta property="og:description" content="%2$s">
                  <meta property="og:url" content="%3$s">
                  <meta property="og:image" content="https://peatying.com/img/og.jpg">
                  <meta property="og:locale" content="es_ES">
                  <meta name="twitter:card" content="summary_large_image">
                  <link rel="icon" href="/img/favicon.png" type="image/png" sizes="144x144">
                  <link rel="apple-touch-icon" href="/img/apple-touch-icon.png">
                  <link rel="stylesheet" href="/styles.css">
                %4$s</head>
                <body class="topic-page">
                  <div class="frame">
                    <header class="topbar">
                      <a href="/" class="wordmark" aria-label="Inicio"><img src="/img/logo.png" alt="" class="logo" width="30" height="30"><span class="wordmark-name">Ray Peat</span></a>
                      <nav class="actions">
                        <a class="ghost" href="/temas">Temas</a>
                        <a class="ghost" href="/">Ir al chat</a>
                      </nav>
                    </header>
                    <main class="topic">
                %5$s    </main>
                  </div>
                </body>
                </html>
                """.formatted(esc(title), esc(description), esc(url), structured, body);
    }

    /** Escapa el texto y convierte cada [n] en un enlace a su fuente de la lista final. */
    static String cites(String text) {
        return esc(text).replaceAll("\\[(\\d+)]", "<a class=\"topic-cite\" href=\"#fuente-$1\">$1</a>");
    }

    /** Sin las citas, para los datos estructurados. */
    static String plain(String text) {
        return text.replaceAll("\\s*\\[\\d+]", "");
    }

    static String esc(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
