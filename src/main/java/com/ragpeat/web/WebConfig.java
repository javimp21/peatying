package com.ragpeat.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Para que el navegador nunca use una versión vieja de la página.
 *
 * El problema: dentro del .jar, Maven pone a todos los archivos la misma fecha fija (1-feb-1980)
 * para que las compilaciones sean reproducibles. El navegador pregunta "¿ha cambiado desde 1980?",
 * el servidor dice que no, y el navegador sigue usando la versión vieja aunque hayamos cambiado la web.
 *
 * La solución: un ETag, una "huella" calculada a partir del CONTENIDO del archivo. Si el archivo
 * cambia, la huella cambia y el navegador descarga la nueva versión; si no, recibe un 304 y no
 * descarga nada. (En application.properties además desactivamos el uso de la fecha.)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Direcciones limpias: peatying.com/about en vez de /about.html. "forward" sirve el archivo sin
     * cambiar la dirección que ve el navegador (no es una redirección). /about.html sigue funcionando,
     * para no romper enlaces ya compartidos.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/about").setViewName("forward:/about.html");
        registry.addViewController("/privacidad").setViewName("forward:/privacidad.html");
        registry.addViewController("/panel").setViewName("forward:/panel.html");
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        // Solo los archivos de la página; la API del chat no lo necesita.
        // "/" NO va aquí: Spring la sirve reenviando internamente a index.html (un "forward"), y con
        // este filtro la respuesta salía vacía. Sin fecha ni huella, el navegador la pide entera
        // cada vez, que para un HTML tan pequeño no importa.
        registration.addUrlPatterns("/index.html", "/app.js", "/styles.css", "/about.html", "/about.js",
                "/privacidad.html", "/contact.js", "/robots.txt", "/sitemap.xml", "/img/*");
        // /temas/* tampoco: /temas/azucar es otro forward (ver TopicPagesController) y saldría vacía.
        return registration;
    }
}
