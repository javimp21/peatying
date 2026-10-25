package com.ragpeat.search;

/** Un chunk encontrado por la búsqueda, con lo necesario para citarlo. sourceType: "article" o "interview". */
public record SearchResult(long chunkId, String slug, String title, String url, String sourceType, String content,
                           double score) {

    public boolean isInterview() {
        return "interview".equals(sourceType);
    }
}
