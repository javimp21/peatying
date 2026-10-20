package com.ragpeat.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limita cuántas preguntas puede hacer cada persona (por IP) y cuántas en total al día.
 * Cada pregunta nos cuesta dinero: sin esto, un script podría gastarnos la API en minutos.
 *
 * Algoritmo de "ventana deslizante": por cada IP guardamos la hora de sus últimas preguntas
 * y contamos cuántas caen dentro de la ventana (por ejemplo, la última hora).
 *
 * Limitación: vive en memoria. Si reinicias la app se resetea, y con varias instancias cada una
 * tendría su propio contador. Para una app pequeña con una instancia es suficiente.
 */
@Component
public class RateLimiter {

    private final int perIpLimit;
    private final Duration window;
    private final int dailyLimit;
    private final Clock clock;

    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();
    private Instant dayStart;
    private int todayCount;

    // Con DOS constructores, Spring no sabe cuál usar: @Autowired le indica este.
    @Autowired
    public RateLimiter(@Value("${chat.limit.per-ip:20}") int perIpLimit,
                       @Value("${chat.limit.window-minutes:60}") int windowMinutes,
                       @Value("${chat.limit.daily:500}") int dailyLimit) {
        this(perIpLimit, Duration.ofMinutes(windowMinutes), dailyLimit, Clock.systemUTC());
    }

    /** Constructor para tests: con un Clock falso podemos "adelantar el tiempo" sin esperar. */
    RateLimiter(int perIpLimit, Duration window, int dailyLimit, Clock clock) {
        this.perIpLimit = perIpLimit;
        this.window = window;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
        this.dayStart = clock.instant();
    }

    /**
     * ¿Puede esta IP hacer otra pregunta ahora? Si sí, la cuenta.
     * synchronized: dos peticiones a la vez no pueden colarse las dos a la vez por el mismo hueco.
     */
    public synchronized boolean tryAcquire(String ip) {
        Instant now = clock.instant();

        if (Duration.between(dayStart, now).toHours() >= 24) {
            dayStart = now;
            todayCount = 0;
            // Una vez al día olvidamos las IPs que ya no tienen preguntas recientes: si no, el mapa
            // crecería con cada visitante nuevo durante meses.
            requestsByIp.values().removeIf(times -> times.isEmpty() || times.peekLast().isBefore(now.minus(window)));
        }
        if (todayCount >= dailyLimit) {
            return false;
        }

        Deque<Instant> times = requestsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        // Quitamos las preguntas que ya se salieron de la ventana.
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(window))) {
            times.pollFirst();
        }
        if (times.size() >= perIpLimit) {
            return false;
        }
        times.addLast(now);
        todayCount++;
        return true;
    }
}
