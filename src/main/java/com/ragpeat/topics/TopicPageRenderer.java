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
 * Convierte el contenido de cada tema en una página HTML fija, en español (static/temas/<slug>.html) y en
 * inglés (static/en/topics/<slug>.html), más el índice de cada idioma y el sitemap.xml con todas las páginas.
 * Sin JavaScript: así cualquier buscador o asistente de IA puede leerla entera.
 * Cada página enlaza con su traducción (hreflang) para que Google sepa que son la misma en dos idiomas.
 */
@Component
public class TopicPageRenderer {

    static final Path STATIC = Path.of("src", "main", "resources", "static");
    static final Path SITEMAP = STATIC.resolve("sitemap.xml");
    static final String SITE = "https://peatying.com";

    /** Todo lo que cambia entre idiomas: carpeta, direcciones y textos fijos de la página. */
    record Lang(String code, String locale, Path out, String base, String home, String topics, String toChat,
                String crumbs, String faq, String sources, String article, String interview, String book,
                String ask, String open, String close, String note, String indexTitle, String indexDescription,
                String indexIntro) {

        String type(String type) {
            return "interview".equals(type) ? interview : "book".equals(type) ? book : article;
        }
    }

    static final Lang ES = new Lang("es", "es_ES", STATIC.resolve("temas"), "/temas", "/", "Temas", "Ir al chat",
            "Temas", "Preguntas frecuentes", "Fuentes", "artículo", "entrevista (transcripción automática)", "libro",
            "Pregúntale al chatbot: ", "«", "»",
            "Resumen hecho con IA a partir de los artículos y entrevistas de Ray Peat que se citan. "
                    + "Puede contener errores y no es consejo médico. Proyecto independiente, sin relación con Ray Peat ni con raypeat.com.",
            "Temas: las ideas de Ray Peat en español",
            "Las ideas de Ray Peat sobre el azúcar, la tiroides, el estrés, los aceites vegetales y más, resumidas en español con sus fuentes.",
            "Lo que pensaba Ray Peat sobre cada tema, resumido en español y con enlaces a sus artículos y entrevistas originales. "
                    + "Para preguntar lo que quieras, usa el <a href=\"/\">chatbot</a>.");

    static final Lang EN = new Lang("en", "en_GB", STATIC.resolve("en").resolve("topics"), "/en/topics", "/en", "Topics",
            "Go to the chat", "Topics", "Frequently asked questions", "Sources", "article",
            "interview (automatic transcript)", "book", "Ask the chatbot: ", "“", "”",
            "AI-generated summary based on the Ray Peat articles and interviews cited. "
                    + "It may contain errors and is not medical advice. Independent project, not affiliated with Ray Peat or raypeat.com.",
            "Topics: Ray Peat's ideas explained",
            "Ray Peat's ideas on sugar, the thyroid, stress, vegetable oils and more, summarised with their sources.",
            "What Ray Peat thought about each topic, summarised with links to his original articles and interviews. "
                    + "To ask anything you like, use the <a href=\"/en\">chatbot</a>.");

    private final JsonMapper json;

    public TopicPageRenderer(JsonMapper json) {
        this.json = json;
    }

    /**
     * @param pages   los temas en español, en el orden del índice.
     * @param english su traducción, en el mismo orden (null donde un tema aún no está traducido).
     */
    public void writeAll(List<Page> pages, List<Page> english) throws IOException {
        List<Page> translated = new ArrayList<>();
        Files.createDirectories(ES.out());
        Files.createDirectories(EN.out());
        for (int i = 0; i < pages.size(); i++) {
            Page es = pages.get(i);
            Page en = english.get(i);
            Files.writeString(ES.out().resolve(es.slug() + ".html"), page(es, ES, en == null ? null : EN.base() + "/" + en.slug()));
            if (en != null) {
                Files.writeString(EN.out().resolve(en.slug() + ".html"), page(en, EN, ES.base() + "/" + es.slug()));
                translated.add(en);
            }
        }
        Files.writeString(ES.out().resolve("index.html"), index(pages, ES, translated.isEmpty() ? null : EN.base()));
        if (!translated.isEmpty()) {
            Files.writeString(EN.out().resolve("index.html"), index(translated, EN, ES.base()));
        }
        Files.writeString(SITEMAP, sitemap(pages, translated));
    }

    // --- Una página de tema ----------------------------------------------------------

    /** @param otherPath la misma página en el otro idioma (/en/topics/sugar), o null si no existe. */
    String page(Page p, Lang t, String otherPath) {
        String url = SITE + t.base() + "/" + p.slug();
        // Corto a propósito (Bing avisa a partir de ~70 caracteres): "Ray Peat y el azúcar · Ray Peat Chatbot".
        String title = p.title();
        StringBuilder body = new StringBuilder();
        body.append("<p class=\"topic-crumbs\"><a href=\"").append(t.base()).append("\">").append(t.crumbs()).append("</a> · Ray Peat</p>\n");
        body.append("<h1>").append(esc(p.title())).append("</h1>\n");
        body.append("<p class=\"topic-intro\">").append(cites(p.intro())).append("</p>\n");
        body.append(askBox(p.question(), t));
        for (Section s : p.sections()) {
            body.append("<h2>").append(esc(s.heading())).append("</h2>\n");
            for (String paragraph : s.paragraphs()) {
                body.append("<p>").append(cites(paragraph)).append("</p>\n");
            }
        }
        body.append("<h2>").append(t.faq()).append("</h2>\n<dl class=\"topic-faq\">\n");
        for (Faq f : p.faq()) {
            body.append("<dt>").append(esc(f.q())).append("</dt>\n<dd>").append(cites(f.a())).append("</dd>\n");
        }
        body.append("</dl>\n");
        body.append(askBox(p.question(), t));
        body.append("<h2>").append(t.sources()).append("</h2>\n<ol class=\"topic-sources\">\n");
        for (int i = 0; i < p.sources().size(); i++) {
            Source s = p.sources().get(i);
            body.append("<li id=\"fuente-").append(i + 1).append("\"><a href=\"").append(esc(s.url()))
                    // Algunos títulos de raypeat.com acaban en ":" ("Regeneration and degeneration:"): fuera.
                    .append("\" target=\"_blank\" rel=\"noopener\">").append(esc(s.title().replaceAll("\\s*:\\s*$", "")))
                    .append("</a> <span>").append(t.type(s.type())).append("</span></li>\n");
        }
        body.append("</ol>\n");
        body.append("<p class=\"topic-note\">").append(esc(t.note())).append("</p>\n");

        // Datos estructurados (schema.org) para Google y los asistentes de IA: un artículo y sus preguntas frecuentes.
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("@context", "https://schema.org");
        article.put("@type", "Article");
        article.put("headline", p.title());
        article.put("description", p.description());
        article.put("inLanguage", t.code());
        article.put("url", url);
        article.put("about", Map.of("@type", "Person", "name", "Ray Peat"));
        article.put("publisher", Map.of("@type", "Organization", "name", "Ray Peat Chatbot", "url", SITE + t.home()));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Faq f : p.faq()) {
            questions.add(Map.of("@type", "Question", "name", f.q(),
                    "acceptedAnswer", Map.of("@type", "Answer", "text", plain(f.a()))));
        }
        Map<String, Object> faqPage = Map.of("@context", "https://schema.org", "@type", "FAQPage", "mainEntity", questions);

        return document(title, p.description(), url, json.writeValueAsString(List.of(article, faqPage)), body.toString(),
                t, otherPath);
    }

    /** El botón que lleva al chat con la pregunta ya escrita (no se envía sola: la persona decide). */
    private String askBox(String question, Lang t) {
        String href = t.home() + "?q=" + URLEncoder.encode(question, StandardCharsets.UTF_8);
        return "<p class=\"topic-ask\"><a href=\"" + esc(href) + "\">" + t.ask() + t.open() + esc(question) + t.close() + " →</a></p>\n";
    }

    // --- Índice de temas ----------------------------------------------------------------

    String index(List<Page> pages, Lang t, String otherPath) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(t.topics()).append("</h1>\n<p class=\"topic-intro\">").append(t.indexIntro())
                .append("</p>\n<ul class=\"topic-index\">\n");
        for (Page p : pages) {
            body.append("<li><a href=\"").append(t.base()).append("/").append(p.slug()).append("\">").append(esc(p.title()))
                    .append("</a> <span>").append(esc(p.description())).append("</span></li>\n");
        }
        body.append("</ul>\n");
        return document(t.indexTitle(), t.indexDescription(), SITE + t.base(), null, body.toString(), t, otherPath);
    }

    // --- Sitemap ------------------------------------------------------------------------

    String sitemap(List<Page> pages, List<Page> english) {
        String today = LocalDate.now().toString();
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- Lo genera TopicsCommand (comando "topics"): las páginas que queremos que aparezcan en Google. -->
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        List<String> urls = new ArrayList<>(List.of("/", "/about", "/temas", "/privacidad", "/en", "/en/about", "/en/privacy"));
        pages.forEach(p -> urls.add(ES.base() + "/" + p.slug()));
        if (!english.isEmpty()) {
            urls.add(EN.base());
            english.forEach(p -> urls.add(EN.base() + "/" + p.slug()));
        }
        for (String path : urls) {
            xml.append("  <url><loc>").append(SITE).append(path).append("</loc><lastmod>").append(today).append("</lastmod></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    // --- Piezas comunes -------------------------------------------------------------------

    private String document(String title, String description, String url, String jsonLd, String body, Lang t, String otherPath) {
        String structured = jsonLd == null ? ""
                : "  <script type=\"application/ld+json\">" + jsonLd.replace("</", "<\\/") + "</script>\n";
        // La misma página en el otro idioma: para Google (hreflang) y para quien lee (botón ES/EN).
        String alternates = "";
        String switcher = "";
        if (otherPath != null) {
            String other = t == ES ? "en" : "es";
            String esUrl = t == ES ? url : SITE + otherPath;
            String enUrl = t == ES ? SITE + otherPath : url;
            alternates = "  <link rel=\"alternate\" hreflang=\"es\" href=\"" + esc(esUrl) + "\">\n"
                    + "  <link rel=\"alternate\" hreflang=\"en\" href=\"" + esc(enUrl) + "\">\n";
            switcher = "        <a class=\"ghost\" href=\"" + esc(otherPath) + "\" hreflang=\"" + other + "\" lang=\"" + other
                    + "\">" + other.toUpperCase() + "</a>\n";
        }
        return """
                <!doctype html>
                <html lang="%6$s">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%1$s · Ray Peat Chatbot</title>
                  <meta name="description" content="%2$s">
                  <meta name="color-scheme" content="light">
                  <link rel="canonical" href="%3$s">
                %8$s  <meta property="og:type" content="article">
                  <meta property="og:site_name" content="Ray Peat Chatbot">
                  <meta property="og:title" content="%1$s">
                  <meta property="og:description" content="%2$s">
                  <meta property="og:url" content="%3$s">
                  <meta property="og:image" content="https://peatying.com/img/og.jpg">
                  <meta property="og:locale" content="%7$s">
                  <meta name="twitter:card" content="summary_large_image">
                  <link rel="icon" href="/img/favicon.png" type="image/png" sizes="144x144">
                  <link rel="apple-touch-icon" href="/img/apple-touch-icon.png">
                  <link rel="stylesheet" href="/styles.css">
                %4$s</head>
                <body class="topic-page">
                  <div class="frame">
                    <header class="topbar">
                      <a href="%9$s" class="wordmark" aria-label="Ray Peat Chatbot"><img src="/img/logo.png" alt="" class="logo" width="30" height="30"><span class="wordmark-name">Ray Peat</span></a>
                      <nav class="actions">
                %12$s        <a class="ghost" href="%10$s">%11$s</a>
                        <a class="ghost" href="%9$s">%13$s</a>
                      </nav>
                    </header>
                    <main class="topic">
                %5$s    </main>
                  </div>
                </body>
                </html>
                """.formatted(esc(title), esc(description), esc(url), structured, body, t.code(), t.locale(), alternates,
                t.home(), t.base(), t.topics(), switcher, t.toChat());
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
