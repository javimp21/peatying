package com.ragpeat.web;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** referrer: la página de la que viene el visitante (document.referrer), o vacío si llega directo. */
    record VisitRequest(String referrer) {}

    @PostMapping("/api/visit")
    public ResponseEntity<Void> visit(@RequestBody(required = false) VisitRequest request, HttpServletRequest http) {
        if (!limiter.tryAcquire(clientInfo.ip(http))) {
            return ResponseEntity.noContent().build(); // no contamos, pero tampoco damos pistas
        }
        // ON CONFLICT DO NOTHING: si este visitante ya contó hoy, no hacemos nada (ni error ni duplicado).
        jdbc.sql("""
                INSERT INTO page_views (day, visitor_hash, referrer) VALUES (CURRENT_DATE, :visitor, :referrer)
                ON CONFLICT DO NOTHING
                """)
                .param("visitor", clientInfo.visitorHash(http))
                .param("referrer", referrerHost(request == null ? null : request.referrer()))
                .update();
        return ResponseEntity.noContent().build();
    }

    /**
     * Solo el dominio de la página de origen: "https://www.google.com/search?q=..." -> "google.com".
     * La dirección completa podría llevar datos personales (búsquedas, ids), así que no se guarda.
     * Vacío, inválido o nuestra propia web (navegar entre páginas de peatying) -> null = directo.
     */
    static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referrer.strip()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            if (host.equals("peatying.com") || host.endsWith(".peatying.com") || host.equals("localhost")) {
                return null;
            }
            return host.length() > 100 ? host.substring(0, 100) : host;
        } catch (IllegalArgumentException e) {
            return null;              // no es una URL válida
        }
    }
}
