package com.ragpeat.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Además, cada IP tiene un tope por día: con solo el de la hora, una IP podría hacer 20 x 24 = 480 preguntas
 * y gastar casi todo el cupo del día de los demás.
 *
 * Los contadores del día vuelven a cero a medianoche UTC (antes, 24 h después de arrancar: cada despliegue
 * movía la hora del reinicio).
 *
 * Limitación: vive en memoria. Si reinicias la app se resetea, y con varias instancias cada una
 * tendría su propio contador. Para una app pequeña con una instancia es suficiente.
 */
@Component
public class RateLimiter {

    private final int perIpLimit;
    private final Duration window;
    private final int perIpDailyLimit;
    private final int dailyLimit;
    private final Clock clock;

    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> todayByIp = new ConcurrentHashMap<>();
    private LocalDate today;
    private int todayCount;

    // Con varios constructores, Spring no sabe cuál usar: @Autowired le indica este.
    @Autowired
    public RateLimiter(@Value("${chat.limit.per-ip:20}") int perIpLimit,
                       @Value("${chat.limit.window-minutes:60}") int windowMinutes,
                       @Value("${chat.limit.per-ip-daily:200}") int perIpDailyLimit,
                       @Value("${chat.limit.daily:1500}") int dailyLimit) {
        this(perIpLimit, Duration.ofMinutes(windowMinutes), perIpDailyLimit, dailyLimit, Clock.systemUTC());
    }

    /** Sin tope diario por IP (para los otros límites de la web: visitas, traducciones, documentos...). */
    RateLimiter(int perIpLimit, Duration window, int dailyLimit, Clock clock) {
        this(perIpLimit, window, Integer.MAX_VALUE, dailyLimit, clock);
    }

    /** Constructor para tests: con un Clock falso podemos "adelantar el tiempo" sin esperar. */
    RateLimiter(int perIpLimit, Duration window, int perIpDailyLimit, int dailyLimit, Clock clock) {
        this.perIpLimit = perIpLimit;
        this.window = window;
        this.perIpDailyLimit = perIpDailyLimit;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
        this.today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * ¿Puede esta IP hacer otra pregunta ahora? Si sí, la cuenta.
     * synchronized: dos peticiones a la vez no pueden colarse las dos a la vez por el mismo hueco.
     */
    public synchronized boolean tryAcquire(String ip) {
        Instant now = clock.instant();

        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!day.equals(today)) {
            today = day;
            todayCount = 0;
            todayByIp.clear();
            // Una vez al día olvidamos las IPs que ya no tienen preguntas recientes: si no, el mapa
            // crecería con cada visitante nuevo durante meses.
            requestsByIp.values().removeIf(times -> times.isEmpty() || times.peekLast().isBefore(now.minus(window)));
        }
        if (todayCount >= dailyLimit || todayByIp.getOrDefault(ip, 0) >= perIpDailyLimit) {
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
        todayByIp.merge(ip, 1, Integer::sum);
        todayCount++;
        return true;
    }

    /** ¿Ha agotado esta IP su ventana? Solo mira, no cuenta nada. No mira el tope global: otro no podría bloquearla. */
    public synchronized boolean atLimit(String ip) {
        Deque<Instant> times = requestsByIp.get(ip);
        if (times == null) {
            return false;
        }
        Instant since = clock.instant().minus(window);
        return times.stream().filter(time -> !time.isBefore(since)).count() >= perIpLimit;
    }
}
