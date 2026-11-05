package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiBotCounterTest {

    @Test
    void reconoceALosRastreadoresDeIa_yNoALosDemas() {
        assertThat(AiBotCounter.bot("Mozilla/5.0 AppleWebKit/537.36; compatible; ChatGPT-User/1.0; +https://openai.com/bot"))
                .isEqualTo("ChatGPT-User");
        assertThat(AiBotCounter.bot("Mozilla/5.0 (compatible; OAI-SearchBot/1.0; +https://openai.com/searchbot)")).isEqualTo("OAI-SearchBot");
        assertThat(AiBotCounter.bot("mozilla/5.0 (compatible; claudebot/1.0)")).isEqualTo("ClaudeBot");
        assertThat(AiBotCounter.bot("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")).isNull();
        assertThat(AiBotCounter.bot("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1")).isNull();
        assertThat(AiBotCounter.bot(null)).isNull();
    }

    @Test
    void soloCuentaPaginas() {
        assertThat(AiBotCounter.page("/")).isEqualTo("/");
        assertThat(AiBotCounter.page("/en/topics/gelatin")).isEqualTo("/en/topics/gelatin");
        assertThat(AiBotCounter.page("/about.html")).isEqualTo("/about.html");
        assertThat(AiBotCounter.page("/r/AbCdEfGhIj")).isEqualTo("/r");
        assertThat(AiBotCounter.page("/styles.css")).isNull();
        assertThat(AiBotCounter.page("/img/logo.png")).isNull();
        assertThat(AiBotCounter.page("/robots.txt")).isNull();
        assertThat(AiBotCounter.page("/api/visit")).isNull();
        assertThat(AiBotCounter.page("/panel")).isNull();
        assertThat(AiBotCounter.page("/" + "x".repeat(300))).isNull();
    }
}
