package com.ragpeat.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Compartir" una respuesta: POST /api/share guarda una copia y devuelve su enlace; GET /r/{id} la enseña.
 *
 * Seguridad: la página la verá cualquiera en nuestro dominio, así que NADA de lo que se publica viene del
 * navegador. La pregunta y la respuesta se leen de nuestra base de datos (y solo con el id secreto de la
 * conversación, como los votos), y de las fuentes que manda la página solo se aceptan direcciones que estén
 * en nuestra tabla de documentos, con el título que tenemos nosotros. Así nadie puede colgar en peatying.com
 * un texto o un enlace inventado.
 */
@RestController
public class ShareController {

    private static final String SITE = "https://peatying.com";
    private static final String QUOTE_PREFIX = "Sobre esta parte de tu respuesta anterior: «";
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ClientInfo clientInfo;
    private final SecureRandom random = new SecureRandom();
    private final RateLimiter limiter = new RateLimiter(20, Duration.ofHours(1), 2000, Clock.systemUTC());

    public ShareController(JdbcClient jdbc, JsonMapper json, ClientInfo clientInfo) {
        this.jdbc = jdbc;
        this.json = json;
        this.clientInfo = clientInfo;
    }

    record SourceRef(int number, String url) {}

    record ShareRequest(UUID conversationId, Long messageId, List<SourceRef> sources) {}

    record ShareResponse(String url) {}

    record Source(int number, String title, String url, String type) {}

    @PostMapping("/api/share")
    public ResponseEntity<?> share(@RequestBody ShareRequest request, HttpServletRequest http) {
        if (request.conversationId() == null || request.messageId() == null) {
            return ResponseEntity.badRequest().build();
        }
        // Ya compartida: el mismo enlace (sin gastar el límite).
        Optional<String> existing = jdbc.sql("SELECT id FROM shared_answers WHERE message_id = :m")
                .param("m", request.messageId()).query(String.class).optional();
        if (existing.isPresent()) {
            return ResponseEntity.ok(new ShareResponse("/r/" + existing.get()));
        }
        if (!limiter.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        Optional<String> answer = jdbc.sql("""
                SELECT content FROM chat_messages
                WHERE id = :m AND conversation_id = :c AND role = 'assistant'
                """).param("m", request.messageId()).param("c", request.conversationId()).query(String.class).optional();
        Optional<String> question = jdbc.sql("""
                SELECT content FROM chat_messages
                WHERE conversation_id = :c AND role = 'user' AND id < :m
                ORDER BY id DESC LIMIT 1
                """).param("m", request.messageId()).param("c", request.conversationId()).query(String.class).optional();
        if (answer.isEmpty() || question.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Fuentes: solo las citadas en la respuesta y solo si existen en nuestra tabla de documentos.
        List<Source> sources = new ArrayList<>();
        Set<Integer> cited = new HashSet<>();
        CITATION.matcher(answer.get()).results().forEach(m -> cited.add(Integer.parseInt(m.group(1))));
        for (SourceRef ref : request.sources() == null ? List.<SourceRef>of() : request.sources()) {
            if (ref == null || ref.url() == null || !cited.contains(ref.number())) continue;
            jdbc.sql("SELECT title, source_type FROM documents WHERE url = :url LIMIT 1")
                    .param("url", ref.url()).query().listOfRows().stream().findFirst()
                    .ifPresent(row -> sources.add(new Source(ref.number(), (String) row.get("title"), ref.url(),
                            (String) row.get("source_type"))));
        }

        String id = newId();
        jdbc.sql("""
                INSERT INTO shared_answers (id, message_id, question, answer, sources)
                VALUES (:id, :m, :q, :a, CAST(:s AS jsonb))
                """)
                .param("id", id).param("m", request.messageId())
                .param("q", withoutQuote(question.get())).param("a", answer.get())
                .param("s", json.writeValueAsString(sources))
                .update();
        return ResponseEntity.ok(new ShareResponse("/r/" + id));
    }

    @GetMapping("/r/{id:[a-zA-Z0-9]{10}}")
    public ResponseEntity<String> page(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbc.sql("""
                UPDATE shared_answers SET views = views + 1 WHERE id = :id
                RETURNING question, answer, sources::text AS sources
                """).param("id", id).query().listOfRows();
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
                    .body(document("Respuesta no encontrada", "", SITE + "/r/" + id,
                            "<h1>No existe</h1><p>Esta respuesta compartida no existe. <a href=\"/\">Pregunta tú</a>.</p>"));
        }
        Map<String, Object> row = rows.getFirst();
        String question = (String) row.get("question");
        String answer = (String) row.get("answer");
        List<Source> sources = json.readValue((String) row.get("sources"), new TypeReference<List<Source>>() {});
        Map<Integer, Source> byNumber = new TreeMap<>();
        sources.forEach(s -> byNumber.put(s.number(), s));

        StringBuilder body = new StringBuilder();
        body.append("<p class=\"topic-crumbs\">Respuesta compartida · <a href=\"/\">Ray Peat Chatbot</a></p>\n");
        body.append("<h1 class=\"share-question\">").append(esc(question)).append("</h1>\n");
        body.append("<div class=\"share-answer\">").append(markdown(answer, byNumber)).append("</div>\n");
        if (!byNumber.isEmpty()) {
            body.append("<h2>Fuentes</h2>\n<ol class=\"topic-sources\">\n");
            for (Source s : byNumber.values()) {
                body.append("<li value=\"").append(s.number()).append("\" id=\"fuente-").append(s.number()).append("\"><a href=\"")
                        .append(esc(s.url())).append("\" target=\"_blank\" rel=\"noopener\">").append(esc(s.title()))
                        .append("</a> <span>").append("interview".equals(s.type()) ? "entrevista" : "artículo").append("</span></li>\n");
            }
            body.append("</ol>\n");
        }
        body.append("<p class=\"topic-ask\"><a href=\"/\">Pregunta tú lo que quieras sobre Ray Peat →</a></p>\n");
        body.append("<p class=\"topic-note\">Respuesta generada por IA a partir de los artículos y entrevistas de Ray Peat. "
                + "Puede contener errores y no es consejo médico.</p>\n");
        String description = plain(answer);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(document(question, description.length() > 155 ? description.substring(0, 152) + "…" : description,
                        SITE + "/r/" + id, body.toString()));
    }

    // --- Piezas ---------------------------------------------------------------------------

    private String newId() {
        char[] id = new char[10];
        for (int i = 0; i < id.length; i++) id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(id);
    }

    /** "Sobre esta parte de tu respuesta anterior: «…»\n\n¿pregunta?" -> "¿pregunta?" */
    static String withoutQuote(String question) {
        if (question.startsWith(QUOTE_PREFIX)) {
            int end = question.indexOf("»\n\n");
            if (end > 0) return question.substring(end + 3).strip();
        }
        return question;
    }

    /** Lo mismo que hace app.js con las respuestas: párrafos, listas, **negrita** y las citas [n] como enlaces. */
    static String markdown(String text, Map<Integer, Source> sources) {
        StringBuilder html = new StringBuilder();
        String list = null;
        List<String> paragraph = new ArrayList<>();
        // split() de Java descarta las líneas vacías del final: por eso se añade una línea en blanco explícita,
        // que hace que el último párrafo también se cierre.
        for (String raw : (text + "\n ").split("\n")) {
            String line = raw.strip();
            Matcher ol = Pattern.compile("^\\d+[.)]\\s+(.*)$").matcher(line);
            Matcher ul = Pattern.compile("^[-*•]\\s+(.*)$").matcher(line);
            String item = ol.matches() ? ol.group(1) : ul.matches() ? ul.group(1) : null;
            String type = ol.matches() ? "ol" : ul.matches() ? "ul" : null;
            if (line.isEmpty() || item != null) {
                if (!paragraph.isEmpty()) html.append("<p>").append(inline(String.join(" ", paragraph), sources)).append("</p>\n");
                paragraph.clear();
            }
            if (item != null) {
                if (!type.equals(list)) {
                    if (list != null) html.append("</").append(list).append(">\n");
                    html.append("<").append(type).append(">\n");
                    list = type;
                }
                html.append("<li>").append(inline(item, sources)).append("</li>\n");
            } else if (!line.isEmpty()) {
                if (list != null) { html.append("</").append(list).append(">\n"); list = null; }
                paragraph.add(line.replaceFirst("^#+\\s*", ""));
            }
        }
        if (list != null) html.append("</").append(list).append(">\n");
        return html.toString();
    }

    private static String inline(String text, Map<Integer, Source> sources) {
        String html = esc(text).replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        Matcher m = CITATION.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            m.appendReplacement(out, sources.containsKey(n)
                    ? "<a class=\"topic-cite\" href=\"#fuente-" + n + "\">" + n + "</a>" : "");
        }
        return m.appendTail(out).toString();
    }

    static String plain(String text) {
        return text.replaceAll("\\*\\*", "").replaceAll("\\s*\\[\\d+]", "").replaceAll("(?m)^\\s*([-*•]|\\d+[.)])\\s+", "")
                .replaceAll("\\s+", " ").strip();
    }

    private static String esc(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }

    private static String document(String title, String description, String url, String body) {
        String shortTitle = title.length() > 90 ? title.substring(0, 87) + "…" : title;
        return """
                <!doctype html>
                <html lang="es">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%1$s · Ray Peat Chatbot</title>
                  <meta name="description" content="%2$s">
                  <meta name="robots" content="noindex">
                  <meta name="color-scheme" content="light">
                  <meta property="og:type" content="article">
                  <meta property="og:site_name" content="Ray Peat Chatbot">
                  <meta property="og:title" content="%1$s">
                  <meta property="og:description" content="%2$s">
                  <meta property="og:url" content="%3$s">
                  <meta property="og:image" content="https://peatying.com/img/og.jpg">
                  <meta property="og:locale" content="es_ES">
                  <meta name="twitter:card" content="summary_large_image">
                  <link rel="icon" href="/img/favicon.png" type="image/png" sizes="144x144">
                  <link rel="stylesheet" href="/styles.css">
                </head>
                <body class="topic-page">
                  <div class="frame">
                    <header class="topbar">
                      <a href="/" class="wordmark" aria-label="Inicio"><img src="/img/logo.png" alt="" class="logo" width="30" height="30"><span class="wordmark-name">Ray Peat</span></a>
                      <nav class="actions"><a class="ghost" href="/temas">Temas</a><a class="ghost" href="/">Ir al chat</a></nav>
                    </header>
                    <main class="topic">
                %4$s    </main>
                  </div>
                </body>
                </html>
                """.formatted(esc(shortTitle), esc(description), esc(url), body);
    }
}
