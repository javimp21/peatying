package com.ragpeat.web;

import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La página llama a POST /api/visit al cargarse: así contamos visitantes aunque no pregunten nada.
 * Anónimo: solo se guarda la huella diaria (ver VisitorHasher), nunca la IP.
 */
@RestController
public class VisitController {

    private final JdbcClient jdbc;
    private final ClientInfo clientInfo;
    /**
     * Cada User-Agent distinto da una huella distinta: sin límite, un script podría llenar la tabla
     * cambiando esa cabecera en cada petición. 30 por IP y hora sobra para cualquier persona real.
     * Es un limitador propio, aparte del de preguntas, para que las visitas no gasten el cupo del chat.
     */
    private final RateLimiter limiter = new RateLimiter(30, Duration.ofHours(1), 100_000, Clock.systemUTC());

    public VisitController(JdbcClient jdbc, ClientInfo clientInfo) {
        this.jdbc = jdbc;
        this.clientInfo = clientInfo;
    }

    @PostMapping("/api/visit")
    public ResponseEntity<Void> visit(HttpServletRequest http) {
        if (!limiter.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.noContent().build(); // no contamos, pero tampoco damos pistas
        }
        // ON CONFLICT DO NOTHING: si este visitante ya contó hoy, no hacemos nada (ni error ni duplicado).
        jdbc.sql("""
                INSERT INTO page_views (day, visitor_hash) VALUES (CURRENT_DATE, :visitor)
                ON CONFLICT DO NOTHING
                """)
                .param("visitor", clientInfo.visitorHash(http))
                .update();
        return ResponseEntity.noContent().build();
    }
}
