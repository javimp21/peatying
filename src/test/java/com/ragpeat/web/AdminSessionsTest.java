package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AdminSessionsTest {

    private final RateLimiterTest.FakeClock clock = new RateLimiterTest.FakeClock();
    private final AdminSessions sessions = new AdminSessions(clock);

    @Test
    void laSesionValeHastaQueCaduca_yNiUnSegundoMas() {
        String session = sessions.open();

        clock.advance(AdminSessions.LIFETIME.minusSeconds(1));
        assertThat(sessions.isValid(session)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(sessions.isValid(session)).isFalse();
    }

    @Test
    void alSalir_esaSesionDejaDeValer_aunqueAlguienHubieraCopiadoLaCookie() {
        String mine = sessions.open();
        String other = sessions.open();

        sessions.close(mine);

        assertThat(sessions.isValid(mine)).isFalse();
        assertThat(sessions.isValid(other)).isTrue();
    }

    @Test
    void valoresInventadosOVacios_noValen() {
        sessions.open();

        assertThat(sessions.isValid(null)).isFalse();
        assertThat(sessions.isValid("")).isFalse();
        assertThat(sessions.isValid("a".repeat(43))).isFalse();
    }

    @Test
    void conElTopeLleno_seCierraLaQueAntesCaduca() {
        String oldest = sessions.open();
        List<String> newer = new ArrayList<>();
        for (int i = 1; i < AdminSessions.MAX_OPEN; i++) {
            clock.advance(Duration.ofMinutes(1));
            newer.add(sessions.open());
        }

        String last = sessions.open();

        assertThat(sessions.isValid(oldest)).isFalse();
        assertThat(newer).allMatch(sessions::isValid);
        assertThat(sessions.isValid(last)).isTrue();
    }
}
