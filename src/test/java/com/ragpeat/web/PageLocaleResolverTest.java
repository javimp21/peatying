package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PageLocaleResolverTest {

    private final PageLocaleResolver resolver = new PageLocaleResolver();

    @Test
    void theAddressDecidesNotTheBrowser() {
        MockHttpServletRequest spanish = new MockHttpServletRequest("GET", "/temas/cafe");
        spanish.addHeader("Accept-Language", "en-US");
        assertThat(resolver.resolveLocale(spanish)).isEqualTo(Locale.of("es"));

        MockHttpServletRequest english = new MockHttpServletRequest("GET", "/en/topics/coffee");
        english.addHeader("Accept-Language", "es-ES");
        assertThat(resolver.resolveLocale(english)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void onlyEnAndItsSubpathsAreEnglish() {
        for (String path : new String[] {"/en", "/en/", "/en/about", "/en/topics/salt"}) {
            assertThat(PageLocaleResolver.localeOf(path)).as(path).isEqualTo(Locale.ENGLISH);
        }
        for (String path : new String[] {"/", "/about", "/temas/sal", "/como-funciona", "/enfermedades"}) {
            assertThat(PageLocaleResolver.localeOf(path)).as(path).isEqualTo(Locale.of("es"));
        }
    }

    @Test
    void errorPagesUseTheAddressThatFailed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/en/topics/nothing");
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }
}
