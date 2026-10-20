package com.ragpeat.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Datos de quien hace la petición: su IP real y su huella anónima diaria.
 * Antes esto estaba dentro de ChatController; ahora lo usan también las visitas, así que vive aparte.
 */
@Component
public class ClientInfo {

    private final VisitorHasher visitorHasher;
    private final boolean trustProxy;

    public ClientInfo(VisitorHasher visitorHasher, @Value("${chat.trust-proxy:false}") boolean trustProxy) {
        this.visitorHasher = visitorHasher;
        this.trustProxy = trustProxy;
    }

    /** Huella anónima del visitante para hoy (ver VisitorHasher). */
    public String visitorHash(HttpServletRequest http) {
        return visitorHasher.hash(ip(http), String.valueOf(http.getHeader("User-Agent")));
    }

    /**
     * La IP de quien pregunta.
     *
     * Detrás de un balanceador o proxy (Caddy, en nuestro caso), la conexión viene del proxy y la IP real
     * llega en la cabecera X-Forwarded-For. Pero esa cabecera la puede escribir cualquiera: si nos
     * fiáramos siempre, un script pondría una IP distinta en cada petición y se saltaría el límite.
     * Por eso:
     *  - Solo la usamos si chat.trust-proxy=true (en producción, detrás de Caddy).
     *  - Tomamos la ÚLTIMA IP de la lista: es la que añade nuestro proxy, la única fiable.
     */
    public String ip(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (trustProxy && forwarded != null && !forwarded.isBlank()) {
            String[] ips = forwarded.split(",");
            return ips[ips.length - 1].strip();
        }
        return http.getRemoteAddr();
    }
}
