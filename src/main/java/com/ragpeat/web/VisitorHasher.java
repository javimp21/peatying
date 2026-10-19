package com.ragpeat.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Convierte (IP + navegador) en una huella anónima para contar visitantes únicos por día.
 *
 * huella = SHA-256(secreto del día + IP + navegador), recortada a 16 caracteres.
 *
 * - SHA-256 es una función "de un solo sentido": de la huella no se puede sacar la IP.
 * - El secreto es aleatorio, vive solo en memoria y cambia cada día: la misma persona da la misma
 *   huella hoy y otra distinta mañana. Así contamos visitantes de un día sin poder seguir a nadie.
 *   (Como el secreto no se guarda, ni nosotros podríamos recalcular la huella de una IP conocida.)
 * - Es la misma idea que usan analíticas respetuosas con la privacidad, como Plausible.
 */
@Component
public class VisitorHasher {

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private LocalDate saltDay;
    private byte[] salt;

    @Autowired   // dos constructores: este es el que debe usar Spring (el otro es para los tests)
    public VisitorHasher() {
        this(Clock.systemUTC());
    }

    VisitorHasher(Clock clock) {
        this.clock = clock;
    }

    public synchronized String hash(String ip, String userAgent) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(saltDay)) {         // nuevo día: nuevo secreto
            salt = new byte[32];
            random.nextBytes(salt);
            saltDay = today;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update((ip + "|" + userAgent).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 siempre existe en Java", e);
        }
    }
}
