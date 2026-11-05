package com.ragpeat.web;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cuenta las lecturas de los rastreadores de IA, por día, rastreador y página. Es la señal de que nos leen, el paso
 * previo a que nos citen: estos programas no ejecutan JavaScript, así que /api/visit nunca los ve.
 * Se ven en el panel. No se guarda la IP. El nombre lo dice el propio rastreador y cualquiera podría imitarlo:
 * es una señal para ver tendencias, no una prueba.
 */
@Component
public class AiBotCounter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AiBotCounter.class);
    private static final int MAX_PAGE_LENGTH = 200;

    /** Como aparecen en la cabecera User-Agent, según cada compañía. Se comparan sin distinguir mayúsculas. */
    static final List<String> BOTS = List.of(
            "ChatGPT-User", "OAI-SearchBot", "GPTBot",
            "Claude-User", "Claude-SearchBot", "ClaudeBot",
            "Perplexity-User", "PerplexityBot",
            "Meta-ExternalFetcher", "Meta-ExternalAgent",
            "DuckAssistBot", "MistralAI-User", "Amazonbot", "Bytespider", "CCBot", "cohere-ai");

    private final JdbcClient jdbc;

    public AiBotCounter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        String bot = "GET".equals(request.getMethod()) ? bot(request.getHeader("User-Agent")) : null;
        String page = bot == null ? null : page(request.getRequestURI());
        // Solo páginas que existen: si no, una dirección inventada en cada petición llenaría la tabla.
        if (page != null && response.getStatus() < 400) {
            count(bot, page);
        }
    }

    private void count(String bot, String page) {
        try {
            jdbc.sql("""
                    INSERT INTO ai_bot_hits (day, bot, page, hits) VALUES (CURRENT_DATE, :bot, :page, 1)
                    ON CONFLICT (day, bot, page) DO UPDATE SET hits = ai_bot_hits.hits + 1
                    """).param("bot", bot).param("page", page).update();
        } catch (DataAccessException e) {
            log.warn("No se pudo contar una lectura de {}: {}", bot, e.getMessage());   // la página ya se ha servido
        }
    }

    /** El nombre del rastreador de IA, o null si esta petición no viene de uno. */
    static String bot(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String agent = userAgent.toLowerCase(Locale.ROOT);
        return BOTS.stream().filter(name -> agent.contains(name.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    /** La página tal como se apunta, o null si no es una página: la API, el panel, imágenes, estilos... */
    static String page(String uri) {
        if (uri.startsWith("/api/") || uri.startsWith("/panel") || uri.length() > MAX_PAGE_LENGTH) {
            return null;
        }
        if (uri.startsWith("/r/")) {
            return "/r";              // cada respuesta compartida tiene su dirección: se cuentan juntas
        }
        String last = uri.substring(uri.lastIndexOf('/') + 1);
        return last.contains(".") && !last.endsWith(".html") ? null : uri;
    }
}
