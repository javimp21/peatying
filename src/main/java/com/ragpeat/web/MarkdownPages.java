package com.ragpeat.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Las páginas en Markdown para agentes de IA (ChatGPT, Claude...): si una petición pide "Accept: text/markdown",
 * se le devuelve el texto de la página sin diseño ni scripts, más fácil de leer para ellos. Un navegador pide
 * text/html y recibe la página de siempre. Las portadas (el chat) tienen un Markdown escrito a mano en static/md/,
 * porque su HTML es casi todo interfaz; el resto se convierte del HTML (lo que hay dentro de <main>).
 * Una dirección que no existe devuelve un 404 en Markdown que explica dónde mirar.
 *
 * Va el último de los filtros: AiBotCounter tiene que ver pasar la respuesta para contar la lectura.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class MarkdownPages extends OncePerRequestFilter {

    static final String SITE = "https://peatying.com";
    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown");
    private static final Pattern TOPIC = Pattern.compile("/(temas|en/topics)/([a-z0-9-]+)");
    private static final Map<String, String> FIXED = Map.ofEntries(
            Map.entry("/", "static/md/index.md"),
            Map.entry("/en", "static/md/en.md"),
            Map.entry("/about", "static/about.html"),
            Map.entry("/en/about", "static/en/about.html"),
            Map.entry("/privacidad", "static/privacidad.html"),
            Map.entry("/en/privacy", "static/en/privacy.html"),
            Map.entry("/privacy", "static/en/privacy.html"),           // en HTML redirige a /en/privacy (WebConfig)
            Map.entry("/como-funciona", "static/como-funciona.html"),
            Map.entry("/en/how-it-works", "static/en/how-it-works.html"),
            Map.entry("/temas", "static/temas/index.html"),
            Map.entry("/en/topics", "static/en/topics/index.html"));

    /** Ya convertidas: las páginas no cambian mientras la aplicación está en marcha. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = normalize(request.getRequestURI());
        if (path.equals("/llms.txt") && "GET".equals(request.getMethod())) {
            // Servido aquí para decir que es UTF-8 (lleva rayas y tildes); como archivo estático saldría sin charset.
            byte[] body = new ClassPathResource("static/llms.txt").getContentAsByteArray();
            response.setContentType("text/plain;charset=UTF-8");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        String source = source(path);
        boolean isPage = source != null;
        if (!("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                || !wantsMarkdown(request.getHeader("Accept"))) {
            if (isPage) response.addHeader("Vary", "Accept");    // la misma dirección tiene dos versiones
            chain.doFilter(request, response);
            return;
        }
        String markdown = isPage ? markdown(path, source) : null;
        if (markdown == null) {
            // Archivos (imágenes, estilos, llms.txt...), la API y las respuestas compartidas (/r/...) siguen su
            // camino; lo demás no existe.
            if (path.startsWith("/api/") || path.startsWith("/r/") || path.substring(path.lastIndexOf('/') + 1).contains(".")) {
                chain.doFilter(request, response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            markdown = """
                    # Page not found

                    There is no page at %s. See the [guide for AI agents](%s/llms.txt), the \
                    [sitemap](%s/sitemap.xml) or the [chat](%s/en).
                    """.formatted(SITE + path, SITE, SITE, SITE);
        }
        byte[] body = markdown.getBytes(StandardCharsets.UTF_8);
        response.setContentType("text/markdown;charset=UTF-8");
        response.addHeader("Vary", "Accept");
        response.setContentLength(body.length);
        if ("GET".equals(request.getMethod())) response.getOutputStream().write(body);
    }

    static String normalize(String uri) {
        return uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    /** El archivo del que sale la página, o null si la dirección no es una de las páginas públicas. */
    static String source(String path) {
        if (FIXED.containsKey(path)) return FIXED.get(path);
        Matcher m = TOPIC.matcher(path);
        return m.matches() ? "static/" + m.group(1) + "/" + m.group(2) + ".html" : null;
    }

    /** Si quien pide prefiere Markdown a HTML (un navegador nunca pide Markdown). */
    static boolean wantsMarkdown(String accept) {
        if (accept == null || !accept.contains("markdown")) return false;
        try {
            List<MediaType> types = MediaType.parseMediaTypes(accept);
            double markdown = 0, html = 0;
            for (MediaType type : types) {
                if (MARKDOWN.includes(type) && !type.isWildcardSubtype()) markdown = Math.max(markdown, type.getQualityValue());
                if (MediaType.TEXT_HTML.isCompatibleWith(type) && !type.isWildcardType() && !type.isWildcardSubtype()) {
                    html = Math.max(html, type.getQualityValue());
                }
            }
            return markdown > 0 && markdown >= html;
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private String markdown(String path, String source) throws IOException {
        String cached = cache.get(path);
        if (cached != null) return cached;
        ClassPathResource resource = new ClassPathResource(source);
        if (!resource.exists()) return null;            // un tema que no existe
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String markdown = source.endsWith(".md") ? text : fromHtml(text, path);
        cache.put(path, markdown);
        return markdown;
    }

    /** El contenido de <main> en Markdown: títulos, párrafos, listas y enlaces (absolutos). */
    static String fromHtml(String html, String path) {
        Document doc = Jsoup.parse(html, SITE + path);
        Element main = doc.selectFirst("main");
        if (main == null) main = doc.body();
        main.select("script, style, svg, form, dialog, button, nav, img, .cover-scroll, .topic-crumbs").remove();
        StringBuilder out = new StringBuilder();
        blocks(main, out);
        String body = out.toString().replaceAll("\n{3,}", "\n\n").strip();
        String title = doc.title();
        if (!body.startsWith("# ") && !body.contains("\n# ") && !title.isBlank()) body = "# " + title + "\n\n" + body;
        return body + "\n\n---\nWeb page: " + SITE + path + "\n";
    }

    private static void blocks(Element parent, StringBuilder out) {
        for (Element e : parent.children()) {
            switch (e.tagName()) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    String text = inline(e);
                    if (!text.isBlank()) out.append("\n").append("#".repeat(e.tagName().charAt(1) - '0')).append(' ').append(text).append("\n\n");
                }
                case "p" -> {
                    String text = inline(e);
                    if (!text.isBlank()) out.append(text).append("\n\n");
                }
                case "ul", "ol" -> {
                    int n = 1;
                    for (Element li : e.children()) {
                        if (!li.tagName().equals("li")) continue;
                        out.append(e.tagName().equals("ol") ? (n++) + ". " : "- ").append(inline(li)).append("\n");
                    }
                    out.append("\n");
                }
                case "blockquote" -> out.append("> ").append(inline(e)).append("\n\n");
                default -> {
                    if (e.children().isEmpty()) {
                        String text = inline(e);
                        if (!text.isBlank()) out.append(text).append("\n\n");
                    } else {
                        blocks(e, out);
                    }
                }
            }
        }
    }

    private static String inline(Element e) {
        StringBuilder out = new StringBuilder();
        for (Node node : e.childNodes()) {
            if (node instanceof TextNode t) {
                out.append(t.text());
            } else if (node instanceof Element child) {
                String text = inline(child).strip();
                switch (child.tagName()) {
                    case "a" -> {
                        String href = child.attr("href");
                        if (text.isEmpty()) break;
                        if (href.startsWith("#")) out.append('[').append(text).append(']');   // nota: [1]
                        else out.append('[').append(text).append("](").append(child.absUrl("href")).append(')');
                    }
                    case "strong", "b" -> out.append("**").append(text).append("**");
                    case "em", "i" -> { if (!text.isEmpty()) out.append('*').append(text).append('*'); }
                    case "br" -> out.append("\n");
                    default -> out.append(inline(child));
                }
            }
        }
        return out.toString().replaceAll("[ \\t\\x0B\\f\\r]+", " ").strip();
    }
}
