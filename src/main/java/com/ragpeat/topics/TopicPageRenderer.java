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
import java.util.Objects;

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
 * El contenido no necesita JavaScript: así cualquier buscador o asistente de IA puede leerla entera.
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
                String indexIntro, String related) {

        String type(String type) {
            return "interview".equals(type) ? interview : "book".equals(type) ? book : article;
        }
    }

    static final Lang ES = new Lang("es", "es_ES", STATIC.resolve("temas"), "/temas", "/", "Temas", "Ir al chat",
            "Temas", "Preguntas frecuentes", "Fuentes", "artículo", "entrevista (transcripción automática)", "libro",
            "Pregúntale al chatbot: ", "«", "»",
            "Resumen hecho con IA a partir de los artículos y entrevistas de Ray Peat que se citan. "
                    + "Puede contener errores y no es consejo médico. Proyecto independiente, sin relación con Ray Peat ni con raypeat.com.",
            "Temas: las ideas de Ray Peat",
            "Las ideas de Ray Peat sobre el azúcar, la tiroides, el estrés, los aceites vegetales y más, resumidas en español con sus fuentes.",
            "Lo que pensaba Ray Peat sobre cada tema, resumido en español y con enlaces a sus artículos y entrevistas originales. "
                    + "Para preguntar lo que quieras, usa el <a href=\"/\">chatbot</a>.",
            "Temas relacionados");

    static final Lang EN = new Lang("en", "en_GB", STATIC.resolve("en").resolve("topics"), "/en/topics", "/en", "Topics",
            "Go to the chat", "Topics", "Frequently asked questions", "Sources", "article",
            "interview (automatic transcript)", "book", "Ask the chatbot: ", "“", "”",
            "AI-generated summary based on the Ray Peat articles and interviews cited. "
                    + "It may contain errors and is not medical advice. Independent project, not affiliated with Ray Peat or raypeat.com.",
            "Topics: Ray Peat's ideas explained",
            "Ray Peat's ideas on sugar, the thyroid, stress, vegetable oils and more, summarised with their sources.",
            "What Ray Peat thought about each topic, summarised with links to his original articles and interviews. "
                    + "To ask anything you like, use the <a href=\"/en\">chatbot</a>.",
            "Related topics");

    private final JsonMapper json;

    public TopicPageRenderer(JsonMapper json) {
        this.json = json;
    }

    /**
     * @param pages   los temas en español, en el orden del índice.
     * @param english su traducción, en el mismo orden (null donde un tema aún no está traducido).
     * @param related por cada tema (slug en español), los temas a los que enlaza (slugs en español).
     */
    public void writeAll(List<Page> pages, List<Page> english, Map<String, List<String>> related) throws IOException {
        List<Page> translated = new ArrayList<>();
        Files.createDirectories(ES.out());
        Files.createDirectories(EN.out());
        // Los enlaces se escriben en español; su posición da la misma página en inglés.
        Map<String, Integer> position = new LinkedHashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            position.put(pages.get(i).slug(), i);
        }
        for (int i = 0; i < pages.size(); i++) {
            Page es = pages.get(i);
            Page en = english.get(i);
            List<Integer> links = related.getOrDefault(es.slug(), List.of()).stream()
                    .map(position::get).filter(Objects::nonNull).toList();
            Files.writeString(ES.out().resolve(es.slug() + ".html"), page(es, ES, en == null ? null : EN.base() + "/" + en.slug(),
                    links.stream().map(pages::get).toList()));
            if (en != null) {
                Files.writeString(EN.out().resolve(en.slug() + ".html"), page(en, EN, ES.base() + "/" + es.slug(),
                        links.stream().map(english::get).filter(Objects::nonNull).toList()));
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

    /**
     * @param otherPath la misma página en el otro idioma (/en/topics/sugar), o null si no existe.
     * @param related   los temas a los que enlaza: el índice ya llega a todos, pero así cada página lleva a las cercanas.
     */
    String page(Page p, Lang t, String otherPath, List<Page> related) {
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
        if (!related.isEmpty()) {
            body.append("<h2>").append(t.related()).append("</h2>\n<ul class=\"topic-related\">\n");
            for (Page other : related) {
                body.append("<li><a href=\"").append(t.base()).append("/").append(other.slug()).append("\">")
                        .append(esc(other.title())).append("</a></li>\n");
            }
            body.append("</ul>\n");
        }
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
        article.put("about", ordered("@type", "Person", "name", "Ray Peat"));
        article.put("publisher", ordered("@type", "Organization", "name", "Ray Peat Chatbot", "url", SITE + t.home()));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Faq f : p.faq()) {
            questions.add(ordered("@type", "Question", "name", f.q(),
                    "acceptedAnswer", ordered("@type", "Answer", "text", plain(f.a()))));
        }
        Map<String, Object> faqPage = ordered("@context", "https://schema.org", "@type", "FAQPage", "mainEntity", questions);
        // Las migas de pan, para que Google muestre "peatying.com › Temas › ..." en vez de la URL.
        Map<String, Object> breadcrumbs = ordered("@context", "https://schema.org", "@type", "BreadcrumbList", "itemListElement", List.of(
                crumb(1, "Ray Peat Chatbot", SITE + t.home()),
                crumb(2, t.topics(), SITE + t.base()),
                crumb(3, p.title(), url)));

        return document(title, p.description(), url, json.writeValueAsString(List.of(article, faqPage, breadcrumbs)), body.toString(),
                t, otherPath);
    }

    /**
     * Como Map.of, pero conserva el orden en que se escriben las claves. Map.of las baraja en cada ejecución: el
     * JSON-LD salía distinto cada vez y git veía cambiadas todas las páginas (y el sitemap les ponía fecha de hoy).
     */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> crumb(int position, String name, String url) {
        return ordered("@type", "ListItem", "position", position, "name", name, "item", url);
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
            xml.append("  <url><loc>").append(SITE).append(path).append("</loc><lastmod>")
                    .append(lastModified(fileOf(path), today)).append("</lastmod></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    /** El archivo que sirve una dirección: "/" y las carpetas son su index.html; el resto, su .html. */
    static Path fileOf(String path) {
        Path file = STATIC.resolve(path.substring(1));
        return path.equals("/") || Files.isDirectory(file) ? file.resolve("index.html") : STATIC.resolve(path.substring(1) + ".html");
    }

    /**
     * La fecha en que cambió de verdad una página, para el lastmod del sitemap. Antes todas llevaban la fecha del
     * día en que se generaba, y Bing (que decide con ese dato qué volver a leer) no podía distinguir las que habían
     * cambiado. Se la preguntamos a git: si el archivo tiene cambios sin guardar en un commit, es hoy (saldrá en el
     * próximo despliegue); si no, la fecha de su último commit. Sin git, hoy, como antes.
     */
    static String lastModified(Path file, String today) {
        try {
            // diff --quiet devuelve 1 si el contenido difiere del último commit (no cuenta los finales de línea).
            if (git("diff", "--quiet", "HEAD", "--", file.toString()).exit() != 0) {
                return today;
            }
            String date = git("log", "-1", "--format=%cs", "--", file.toString()).output();
            return date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : today;   // vacío: archivo nuevo, aún sin commit
        } catch (IOException | InterruptedException e) {
            return today;
        }
    }

    private record GitResult(int exit, String output) {}

    private static GitResult git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new GitResult(process.waitFor(), output);
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
                  <meta property="og:image" content="https://peatying.com/img/og-mascota.jpg">
                  <meta property="og:locale" content="%7$s">
                  <meta name="twitter:card" content="summary_large_image">
                  <link rel="icon" href="/img/favicon-mascota.png" type="image/png" sizes="144x144">
                  <link rel="apple-touch-icon" href="/img/apple-touch-icon.png">
                  <script src="/theme.js"></script>
                  <script src="/visit.js"></script>
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
