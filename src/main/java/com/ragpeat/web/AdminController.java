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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los datos del panel privado (peatying.com/panel): métricas y conversaciones completas.
 *
 * Protegido con una clave secreta (admin.token, que deploy.sh lee de Parameter Store): la página la manda en
 * la cabecera "Authorization: Bearer ...". Sin clave configurada, estas direcciones no existen (404).
 * Un límite de intentos fallidos por IP impide probar claves a lo loco.
 */
@RestController
public class AdminController {

    // Precios de Claude Haiku 5.5 en dólares por token (0,10 y 0,50 $ por millón), como en StatsCommand.
    private static final double INPUT_PRICE = 0.10 / 1_000_000, OUTPUT_PRICE = 0.50 / 1_000_000;

    private final JdbcClient jdbc;
    private final ClientInfo clientInfo;
    private final byte[] token;
    private final RateLimiter failures = new RateLimiter(10, Duration.ofHours(1), 1000, Clock.systemUTC());

    public AdminController(JdbcClient jdbc, ClientInfo clientInfo, @Value("${admin.token:}") String token) {
        this.jdbc = jdbc;
        this.clientInfo = clientInfo;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** null si la clave es buena; si no, la respuesta de error que hay que devolver. */
    private ResponseEntity<?> denied(HttpServletRequest http) {
        if (token.length < 20) {
            return ResponseEntity.notFound().build();          // panel desactivado
        }
        String header = http.getHeader("Authorization");
        byte[] given = (header != null && header.startsWith("Bearer ") ? header.substring(7) : "")
                .getBytes(StandardCharsets.UTF_8);
        // isEqual compara siempre todos los bytes: el tiempo de respuesta no da pistas de cuántos acertaste.
        if (MessageDigest.isEqual(given, token)) {
            return null;
        }
        if (!failures.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.status(429).build();
        }
        return ResponseEntity.status(401).build();
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
                GROUP BY 1 ORDER BY 2 DESC LIMIT 15
                """).query().listOfRows());
        result.put("locations", jdbc.sql("""
                SELECT coalesce(country, 'Desconocido') AS country, coalesce(city, '') AS city, count(*) AS visitors
                FROM page_views WHERE day > CURRENT_DATE - 30
                GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 20
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
                SELECT c.id, c.created_at AS started, c.internal,
                       p.country, p.city, p.referrer
                FROM conversations c
                LEFT JOIN page_views p ON p.visitor_hash = c.visitor_hash AND p.day = c.created_at::date
                WHERE (:internas OR NOT c.internal)
                  AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.conversation_id = c.id)
                ORDER BY c.created_at DESC
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
