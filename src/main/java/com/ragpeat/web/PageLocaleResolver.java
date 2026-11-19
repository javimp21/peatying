package com.ragpeat.web;

import java.util.Locale;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Decide el idioma de la página por su dirección, no por el navegador del visitante.
 *
 * El problema: Spring pone la cabecera Content-Language con el Accept-Language del visitante, y si no
 * manda ninguno (Googlebot y Bingbot no lo mandan) usa el idioma del servidor. Resultado: páginas en
 * español con "Content-Language: en-US". Bing no usa hreflang y sí esta cabecera para saber el idioma,
 * así que le llegaba una señal que contradice al contenido.
 *
 * La solución: todo lo que cuelga de /en está en inglés y el resto en español, sea quien sea el visitante.
 */
public class PageLocaleResolver implements LocaleResolver {

    static final Locale SPANISH = Locale.of("es");

    static Locale localeOf(String path) {
        return path.equals("/en") || path.startsWith("/en/") ? Locale.ENGLISH : SPANISH;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        // En una página de error la dirección es /error; el idioma lo marca la que falló.
        Object failed = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = failed instanceof String uri ? uri : request.getRequestURI();
        return localeOf(path);
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("El idioma lo decide la dirección de la página; no se puede cambiar.");
    }
}
