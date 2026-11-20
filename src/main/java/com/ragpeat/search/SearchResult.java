package com.ragpeat.search;

/**
 * Un chunk encontrado por la búsqueda, con lo necesario para citarlo.
 * sourceType: "article", "interview", "book" o "newsletter".
 * mixedAuthors: página de una revista con varios autores (Townsend Letter): no todo el texto es de Peat.
 */
public record SearchResult(long chunkId, String slug, String title, String url, String sourceType, String content,
                           double score, boolean mixedAuthors) {

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
            case "newsletter" -> mixedAuthors
                    ? "NEWSLETTER (página escaneada de la revista Townsend Letter: en la misma página hay cartas y artículos de OTROS autores)"
                    : "NEWSLETTER";
            default -> "ARTÍCULO";
        };
    }
}
