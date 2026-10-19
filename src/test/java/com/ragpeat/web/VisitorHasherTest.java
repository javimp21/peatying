package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class VisitorHasherTest {

    @Test
    void mismaPersonaMismoDia_mismaHuella_yOtroDiaOtraDistinta() {
        RateLimiterTest.FakeClock clock = new RateLimiterTest.FakeClock();
        VisitorHasher hasher = new VisitorHasher(clock);

        String morning = hasher.hash("1.2.3.4", "Firefox");
        clock.advance(Duration.ofHours(5));
        String afternoon = hasher.hash("1.2.3.4", "Firefox");
        String someoneElse = hasher.hash("5.6.7.8", "Firefox");
        clock.advance(Duration.ofDays(1));
        String tomorrow = hasher.hash("1.2.3.4", "Firefox");

        assertThat(afternoon).isEqualTo(morning);       // el mismo día se cuenta como un visitante
        assertThat(someoneElse).isNotEqualTo(morning);
        assertThat(tomorrow).isNotEqualTo(morning);     // al día siguiente no se le puede seguir
        assertThat(morning).hasSize(16).doesNotContain("1.2.3.4");
    }
}
