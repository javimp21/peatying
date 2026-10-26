package com.ragpeat.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Direcciones limpias para las páginas de temas: /temas/azucar sirve static/temas/azucar.html
 * (las genera el comando "topics"). Como /about en WebConfig, es un "forward": la dirección no cambia.
 */
@Controller
public class TopicPagesController {

    @GetMapping({"/temas", "/temas/"})
    public String index() {
        return "forward:/temas/index.html";
    }

    // Solo minúsculas, números y guiones: nada de "../" ni otros caminos raros.
    @GetMapping("/temas/{slug:[a-z0-9-]+}")
    public String topic(@PathVariable String slug) {
        return "forward:/temas/" + slug + ".html";
    }
}
