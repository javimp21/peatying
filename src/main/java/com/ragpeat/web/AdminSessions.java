package com.ragpeat.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sesiones del panel privado. La clave del panel (de Parameter Store) solo se usa para abrir una sesión; después el
 * navegador se identifica con un valor aleatorio que caduca y que guarda una cookie HttpOnly (ver AdminController).
 * Así la clave no se queda guardada en el navegador ni la puede leer ningún JavaScript.
 *
 * Viven solo en memoria: al reiniciar la app (cada despliegue o al cambiar la clave), hay que volver a entrar.
 * Se guarda la huella SHA-256 de cada valor, no el valor: un volcado de memoria no da sesiones válidas.
 */
class AdminSessions {

    /** Larga a propósito: el panel se mira a menudo, y una cookie HttpOnly no se puede leer para robarla. */
    static final Duration LIFETIME = Duration.ofDays(7);
    /** Sesiones abiertas a la vez como mucho (solo entra quien tiene la clave: con esto sobra). */
    static final int MAX_OPEN = 20;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Huella del valor de la cookie -> cuándo caduca. */
    private final Map<String, Instant> open = new ConcurrentHashMap<>();

    AdminSessions(Clock clock) {
        this.clock = clock;
    }

    /** Abre una sesión y devuelve el valor para la cookie. */
    synchronized String open() {
        Instant now = clock.instant();
        open.values().removeIf(expires -> !now.isBefore(expires));
        // Si se llega al tope, se cierra la que antes caduca: nunca crece sin límite.
        while (open.size() >= MAX_OPEN) {
            open.entrySet().stream().min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> open.remove(oldest.getKey()));
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        open.put(fingerprint(value), now.plus(LIFETIME));
        return value;
    }

    /** ¿Es este valor de una sesión abierta y sin caducar? */
    boolean isValid(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        Instant expires = open.get(fingerprint(value));
        return expires != null && clock.instant().isBefore(expires);
    }

    void close(String value) {
        if (value != null && !value.isEmpty()) {
            open.remove(fingerprint(value));
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 siempre existe en Java", e);
        }
    }
}
