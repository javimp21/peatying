package com.ragpeat.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private static SearchResult r(long id, String type, double score) {
        return new SearchResult(id, "s" + id, "t", "u", type, "c", score, false);
    }

    @Test
    void trasReordenar_seRespetaElRepartoEntreEscritoYEntrevistas() {
        // Ordenados por la puntuación del reranker: las cuatro mejores son entrevistas.
        List<SearchResult> sorted = List.of(r(1, "interview", 0.9), r(2, "interview", 0.8), r(3, "interview", 0.7),
                r(4, "interview", 0.6), r(5, "article", 0.5), r(6, "newsletter", 0.4), r(7, "book", 0.3),
                r(8, "article", 0.2));

        List<SearchResult> chosen = SearchService.withQuotas(sorted, 5);

        assertThat(chosen).extracting(SearchResult::chunkId).containsExactly(1L, 2L, 5L, 6L, 7L);
    }

    @Test
    void siUnTipoNoLlenaSuCupo_elHuecoEsParaElOtro() {
        List<SearchResult> sorted = List.of(r(1, "article", 0.9), r(2, "interview", 0.8), r(3, "article", 0.7),
                r(4, "article", 0.6), r(5, "article", 0.5));

        assertThat(SearchService.withQuotas(sorted, 5)).hasSize(5);
    }
}
