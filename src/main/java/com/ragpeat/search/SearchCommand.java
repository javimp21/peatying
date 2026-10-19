package com.ragpeat.search;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Para probar la búsqueda desde la terminal:
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar search "¿Es bueno comer manzanas?"
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar search "¿Es bueno comer manzanas?" "apple fruit"
 * El segundo texto (opcional) son las palabras clave en inglés para la búsqueda por palabras.
 */
@Component
public class SearchCommand implements CommandLineRunner {

    private final SearchService searchService;

    public SearchCommand(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public void run(String... args) {
        if (args.length < 2 || !args[0].equals("search")) {
            return;
        }
        String query = args[1];
        String keywords = args.length > 2 ? args[2] : null;

        List<SearchResult> results = searchService.search(query, keywords, 5);

        System.out.println("\nPregunta: " + query + (keywords != null ? "   [palabras clave: " + keywords + "]" : ""));
        for (SearchResult r : results) {
            System.out.printf("[%.4f] %s%n         %s%n         %s...%n",
                    r.score(), r.title(), r.url(),
                    r.content().substring(0, Math.min(160, r.content().length())).replace("\n", " "));
        }
    }
}
