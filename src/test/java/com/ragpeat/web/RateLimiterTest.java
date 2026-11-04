package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** Un reloj que solo avanza cuando se lo decimos. */
    static class FakeClock extends Clock {
        Instant now = Instant.parse("2026-10-20T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void cadaIpTieneSuLimite_ySeRecuperaAlPasarLaVentana() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(60), 100, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();   // tercera en la misma hora: no
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();    // otra persona sí puede

        clock.advance(Duration.ofMinutes(61));
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();    // pasada la hora, vuelve a poder
    }

    @Test
    void atLimit_soloMira_yNoDependeDeLoQueHaganOtros() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(60), 2, clock);

        assertThat(limiter.atLimit("1.1.1.1")).isFalse();
        assertThat(limiter.atLimit("1.1.1.1")).isFalse();      // mirar no gasta intentos
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("1.1.1.1");
        assertThat(limiter.atLimit("1.1.1.1")).isTrue();
        assertThat(limiter.atLimit("2.2.2.2")).isFalse();      // aunque el tope global ya esté lleno

        clock.advance(Duration.ofMinutes(61));
        assertThat(limiter.atLimit("1.1.1.1")).isFalse();
    }

    @Test
    void elLimiteDiarioEsParaTodos() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter(10, Duration.ofMinutes(60), 2, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();
        assertThat(limiter.tryAcquire("3.3.3.3")).isFalse();   // tope del día alcanzado

        clock.advance(Duration.ofHours(24));
        assertThat(limiter.tryAcquire("3.3.3.3")).isTrue();
    }

    @Test
    void cadaIpTieneUnTopeDiario_aunqueRespeteElDeLaHora() {
        FakeClock clock = new FakeClock();   // 10:00 UTC
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(60), 3, 100, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        clock.advance(Duration.ofMinutes(61));
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();  // la hora lo permitiría, pero ya lleva 3 hoy
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();   // otra IP no se ve afectada

        clock.advance(Duration.ofHours(13));                   // 00:01 UTC del día siguiente
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test
    void elDiaEmpiezaAMedianocheUtc_noVeinticuatroHorasDespuesDeArrancar() {
        FakeClock clock = new FakeClock();   // arranca a las 10:00 UTC
        RateLimiter limiter = new RateLimiter(10, Duration.ofMinutes(60), 1, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("2.2.2.2")).isFalse();
        clock.advance(Duration.ofHours(14));                   // 00:00 UTC: solo han pasado 14 horas
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();
    }
}
