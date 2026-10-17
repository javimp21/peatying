package com.ragpeat.ingest;

/**
 * Un artículo descargado: de dónde viene, cómo se llama y su texto limpio.
 * Los párrafos del texto van separados por una línea en blanco.
 */
public record Article(String url, String title, String text) {

    /** Nombre corto sacado de la URL: ".../articles/gelatin.shtml" -> "gelatin". */
    public String slug() {
        String file = url.substring(url.lastIndexOf('/') + 1);
        return file.replace(".shtml", "");
    }
}
