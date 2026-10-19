package com.ragpeat.search;

/** Un chunk encontrado por la búsqueda, con lo necesario para citarlo. */
public record SearchResult(long chunkId, String title, String url, String content, double score) {}
