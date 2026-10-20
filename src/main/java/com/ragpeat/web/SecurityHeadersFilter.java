package com.ragpeat.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabeceras de seguridad en todas las respuestas. Son instrucciones para el navegador del usuario:
 * no cambian lo que hace la página, pero le cierran puertas a un atacante.
 *
 *  - Content-Security-Policy: la página solo puede cargar scripts, estilos, fuentes e imágenes de
 *    nuestro propio dominio. Si alguien lograra colar HTML en una respuesta (no debería: app.js lo
 *    escapa todo), un <script> o un <img> hacia otra web no se ejecutaría. frame-ancestors 'none'
 *    impide que otra web nos meta dentro de un iframe para engañar al usuario (clickjacking).
 *  - X-Content-Type-Options: el navegador no "adivina" el tipo de un archivo; usa el que decimos.
 *  - Referrer-Policy: al pulsar un enlace a raypeat.com solo viaja nuestro dominio, no la URL completa.
 *  - Permissions-Policy: la página no puede pedir cámara, micrófono ni ubicación.
 *
 * El HTTPS obligatorio (Strict-Transport-Security) lo pone Caddy, que es quien sirve el HTTPS.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = String.join("; ",
            "default-src 'self'",
            "img-src 'self' data:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        chain.doFilter(request, response);
    }
}
