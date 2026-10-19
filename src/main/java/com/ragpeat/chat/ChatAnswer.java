package com.ragpeat.chat;

import java.util.SortedMap;

import com.ragpeat.search.SearchResult;

/**
 * La respuesta del bot: el texto, los fragmentos que CITA (número [n] -> fragmento)
 * y lo que ha costado en tokens.
 */
public record ChatAnswer(String text, SortedMap<Integer, SearchResult> citedSources, int searches,
                         long inputTokens, long outputTokens) {}
