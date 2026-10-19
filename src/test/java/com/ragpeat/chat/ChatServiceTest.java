package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SortedMap;

import com.ragpeat.search.SearchResult;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private final List<SearchResult> sources = List.of(
            new SearchResult(10, "Gelatin", "url1", "texto 1", 0.03),
            new SearchResult(11, "Sugar", "url2", "texto 2", 0.02),
            new SearchResult(12, "Thyroid", "url3", "texto 3", 0.01));

    @Test
    void soloDevuelveLosFragmentosCitados_ordenados_ySinRepetir() {
        SortedMap<Integer, SearchResult> cited =
                ChatService.citedIn("Según [3], y también [1]. Otra vez [3].", sources);

        assertThat(cited.keySet()).containsExactly(1, 3);
        assertThat(cited.get(3).title()).isEqualTo("Thyroid");
    }

    @Test
    void alPasarElHistorial_seQuitanLosNumerosDeCita() {
        assertThat(ChatService.withoutCitations("El azúcar protege [1]. Y la sal también [2][3]."))
                .isEqualTo("El azúcar protege. Y la sal también.");
    }

    @Test
    void ignoraCitasQueNoExisten() {
        SortedMap<Integer, SearchResult> cited = ChatService.citedIn("Algo [7] y nada [0].", sources);

        assertThat(cited).isEmpty();
    }
}
