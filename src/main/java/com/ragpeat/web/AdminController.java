package com.ragpeat.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/**
 * Los datos del panel privado (peatying.com/panel): métricas y conversaciones completas.
 *
 * Protegido con una clave secreta (admin.token, que deploy.sh lee de Parameter Store). La página la manda una vez, en
 * la cabecera "Authorization: Bearer ...", a POST /api/admin/session, y recibe una sesión en una cookie HttpOnly (ver
 * AdminSessions): la clave no se queda en el navegador y ningún JavaScript puede leer la sesión.
 * Sin clave configurada, estas direcciones no existen (404).
 * Un límite de intentos fallidos por IP impide probar claves a lo loco.
 */
@RestController
public class AdminController {

    // Precios de Claude Haiku 5.5 en dólares por token (0,10 y 0,50 $ por millón), como en StatsCommand.
    private static final double INPUT_PRICE = 0.10 / 1_000_000, OUTPUT_PRICE = 0.50 / 1_000_000;

    static final String SESSION_COOKIE = "rp_admin";

    private final JdbcClient jdbc;
    private final ClientInfo clientInfo;
    private final byte[] token;
    private final RateLimiter failures = new RateLimiter(10, Duration.ofHours(1), 1000, Clock.systemUTC());
    private final AdminSessions sessions = new AdminSessions(Clock.systemUTC());

    public AdminController(JdbcClient jdbc, ClientInfo clientInfo, @Value("${admin.token:}") String token) {
        this.jdbc = jdbc;
        this.clientInfo = clientInfo;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    private boolean disabled() {
        return token.length < 20;
    }

    /** null si la clave de la cabecera es buena; si no, la respuesta de error que hay que devolver. */
    private ResponseEntity<?> wrongKey(HttpServletRequest http) {
        String ip = clientInfo.ip(http);
        // Antes de comparar: pasado el límite no se mira la clave, o se podría seguir probando sin freno.
        if (failures.atLimit(ip)) {
            return ResponseEntity.status(429).build();
        }
        String header = http.getHeader("Authorization");
        byte[] given = (header != null && header.startsWith("Bearer ") ? header.substring(7) : "")
                .getBytes(StandardCharsets.UTF_8);
        // isEqual compara siempre todos los bytes: el tiempo de respuesta no da pistas de cuántos acertaste.
        if (MessageDigest.isEqual(given, token)) {
            return null;
        }
        failures.tryAcquire(ip);
        return ResponseEntity.status(401).build();
    }

    /** Entrar: si la clave es buena, abre una sesión y la deja en la cookie. */
    @PostMapping("/api/admin/session")
    public ResponseEntity<?> login(HttpServletRequest http) {
        if (disabled()) {
            return ResponseEntity.notFound().build();          // panel desactivado
        }
        ResponseEntity<?> no = wrongKey(http);
        if (no != null) {
            return no;
        }
        closeSession(http);                                    // la de antes de este navegador, si la había
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(sessions.open(), AdminSessions.LIFETIME).toString()).build();
    }

    /** Salir: la sesión deja de valer aunque alguien hubiera copiado la cookie. */
    @DeleteMapping("/api/admin/session")
    public ResponseEntity<?> logout(HttpServletRequest http) {
        if (disabled()) {
            return ResponseEntity.notFound().build();
        }
        closeSession(http);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private void closeSession(HttpServletRequest http) {
        Cookie cookie = WebUtils.getCookie(http, SESSION_COOKIE);
        if (cookie != null) {
            sessions.close(cookie.getValue());
        }
    }

    /**
     * HttpOnly: JavaScript no la ve. Secure: solo por HTTPS (los navegadores la aceptan también en http://localhost).
     * SameSite=Strict: otra web no puede hacer que el navegador la mande. Path: solo viaja a /api/admin.
     */
    static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/admin").maxAge(maxAge).build();
    }

    /**
     * null si la petición trae una sesión abierta o la clave en la cabecera; si no, la respuesta de error.
     * La clave sigue valiendo para los scripts de revisión: ningún navegador la guarda, y otra web no puede añadirla.
     * Sin cookie ni cabecera no cuenta como intento fallido: es solo abrir la página sin haber entrado.
     */
    private ResponseEntity<?> denied(HttpServletRequest http) {
        if (disabled()) {
            return ResponseEntity.notFound().build();          // panel desactivado
        }
        Cookie cookie = WebUtils.getCookie(http, SESSION_COOKIE);
        if (cookie != null && sessions.isValid(cookie.getValue())) {
            return null;
        }
        return http.getHeader("Authorization") == null ? ResponseEntity.status(401).build() : wrongKey(http);
    }

    /** Las cifras: por día, de dónde llegan, de dónde son y totales. Sin nuestras conversaciones. */
    @GetMapping("/api/admin/summary")
    public ResponseEntity<?> summary(HttpServletRequest http) {
        ResponseEntity<?> no = denied(http);
        if (no != null) {
            return no;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visits", jdbc.sql("""
                SELECT v.day::text AS day, count(*) AS visitors,
                       (SELECT count(DISTINCT c.visitor_hash) FROM conversations c
                         WHERE c.created_at::date = v.day AND NOT c.internal) AS askers
                FROM page_views v
                WHERE v.day > CURRENT_DATE - 60
                GROUP BY v.day ORDER BY v.day
                """).query().listOfRows());
        List<Map<String, Object>> daily = jdbc.sql("""
                SELECT c.created_at::date::text AS day,
                       count(DISTINCT c.id) AS conversations,
                       count(*) FILTER (WHERE m.role = 'user') AS questions,
                       round(avg(m.latency_ms) / 1000.0, 1) AS seconds,
                       coalesce(sum(m.input_tokens), 0) AS input_tokens,
                       coalesce(sum(m.output_tokens), 0) AS output_tokens
                FROM conversations c JOIN chat_messages m ON m.conversation_id = c.id
                WHERE NOT c.internal AND c.created_at > now() - interval '60 days'
                GROUP BY 1 ORDER BY 1
                """).query().listOfRows();
        for (Map<String, Object> d : daily) {
            d.put("cost", ((Number) d.remove("input_tokens")).longValue() * INPUT_PRICE
                    + ((Number) d.remove("output_tokens")).longValue() * OUTPUT_PRICE);
        }
        result.put("daily", daily);
        result.put("referrers", jdbc.sql("""
                SELECT coalesce(referrer, 'directo') AS origin, count(*) AS visitors
                FROM page_views WHERE day > CURRENT_DATE - 30
                GROUP BY 1 ORDER BY 2 DESC LIMIT 100
                """).query().listOfRows());
        result.put("locations", jdbc.sql("""
                SELECT coalesce(country, 'Desconocido') AS country, count(*) AS visitors
                FROM page_views WHERE day > CURRENT_DATE - 30
                GROUP BY 1 ORDER BY 2 DESC LIMIT 250   -- todos los países (hay unos 200)
                """).query().listOfRows());
        result.put("votes", jdbc.sql("""
                SELECT count(*) FILTER (WHERE m.feedback = 1) AS up,
                       count(*) FILTER (WHERE m.feedback = -1) AS down,
                       count(*) FILTER (WHERE m.role = 'assistant') AS answers
                FROM chat_messages m JOIN conversations c ON c.id = m.conversation_id
                WHERE NOT c.internal
                """).query().singleRow());
        return ResponseEntity.ok(result);
    }

    /**
     * Las últimas conversaciones con todos sus mensajes (preguntas ya sin datos personales: ver PersonalData).
     * internas=true incluye también las nuestras (marcadas con ?interno), para probar cosas.
     */
    @GetMapping("/api/admin/conversations")
    public ResponseEntity<?> conversations(HttpServletRequest http,
                                           @RequestParam(defaultValue = "false") boolean internas,
                                           @RequestParam(defaultValue = "150") int limit) {
        ResponseEntity<?> no = denied(http);
        if (no != null) {
            return no;
        }
        List<Map<String, Object>> conversations = jdbc.sql("""
                SELECT c.id, c.created_at AS started, c.internal, c.document_name, c.document_text,
                       (SELECT max(m.created_at) FROM chat_messages m WHERE m.conversation_id = c.id) AS last_at,
                       p.country, p.referrer
                FROM conversations c
                LEFT JOIN page_views p ON p.visitor_hash = c.visitor_hash AND p.day = c.created_at::date
                WHERE (:internas OR NOT c.internal)
                  AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.conversation_id = c.id)
                -- Primero las que tienen la pregunta más reciente, aunque la conversación empezara antes.
                ORDER BY last_at DESC NULLS LAST
                LIMIT :limit
                """)
                .param("internas", internas)
                .param("limit", Math.min(Math.max(limit, 1), 500))
                .query().listOfRows();
        if (conversations.isEmpty()) {
            return ResponseEntity.ok(conversations);
        }
        List<UUID> ids = conversations.stream().map(c -> (UUID) c.get("id")).toList();
        Map<UUID, List<Map<String, Object>>> byConversation = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.sql("""
                SELECT conversation_id, role, content, created_at AS at, feedback, feedback_reason, latency_ms
                FROM chat_messages WHERE conversation_id IN (:ids) ORDER BY id
                """).param("ids", ids).query().listOfRows()) {
            byConversation.computeIfAbsent((UUID) m.remove("conversation_id"), k -> new ArrayList<>()).add(m);
        }
        conversations.forEach(c -> c.put("messages", byConversation.getOrDefault((UUID) c.get("id"), List.of())));
        return ResponseEntity.ok(conversations);
    }
}
