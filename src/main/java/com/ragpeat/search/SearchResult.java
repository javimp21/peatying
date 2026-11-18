package com.ragpeat.search;

/**
 * Un chunk encontrado por la búsqueda, con lo necesario para citarlo.
 * sourceType: "article", "interview", "book" o "newsletter".
 */
public record SearchResult(long chunkId, String slug, String title, String url, String sourceType, String content,
                           double score) {

    public boolean isInterview() {
        return "interview".equals(sourceType);
    }

    public boolean isBook() {
        return "book".equals(sourceType);
    }

    /** Libros y newsletters tienen derechos de autor: su texto no se manda al navegador ni se publica. */
    public boolean isCopyrighted() {
        return isBook() || "newsletter".equals(sourceType);
    }

    /** Cómo se presenta el fragmento a Claude. */
    public String label() {
        return switch (sourceType) {
            case "interview" -> "ENTREVISTA (transcripción automática: NO citar entre comillas)";
            case "book" -> "LIBRO";
            case "newsletter" -> "NEWSLETTER";
            default -> "ARTÍCULO";
        };
    }
}
