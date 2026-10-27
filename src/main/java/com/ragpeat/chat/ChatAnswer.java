package com.ragpeat.chat;

import java.util.List;
import java.util.SortedMap;

import com.ragpeat.search.SearchResult;

/**
 * La respuesta del bot: el texto, los fragmentos que CITA (número [n] -> fragmento)
 * y lo que ha costado en tokens.
 * seen y queries (todos los fragmentos que recibió y las búsquedas que hizo) sirven para el examen:
 * para saber si un fallo viene de la búsqueda o de cómo escribe la respuesta.
 */
public record ChatAnswer(String text, SortedMap<Integer, SearchResult> citedSources, int searches,
                         long inputTokens, long outputTokens, List<SearchResult> seen, List<String> queries) {

    public ChatAnswer(String text, SortedMap<Integer, SearchResult> citedSources, int searches,
                      long inputTokens, long outputTokens) {
        this(text, citedSources, searches, inputTokens, outputTokens, List.of(), List.of());
    }
}
