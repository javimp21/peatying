package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.ragpeat.search.SearchResult;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private final List<SearchResult> sources = List.of(
            new SearchResult(10, "gelatin", "Gelatin", "url1", "article", "texto 1", 0.03),
            new SearchResult(11, "sugar", "Sugar", "url2", "article", "texto 2", 0.02),
            new SearchResult(12, "thyroid", "Thyroid", "url3", "article", "texto 3", 0.01));

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
    void elHistorial_recortaLasRespuestasAntiguas_peroNoLasPreguntasNiLasDosUltimas() {
        String larga = "x".repeat(2000);
        String contexto = "Estuve 17 años en prisión, tomo tiroides por mi cuenta. " + "y".repeat(2000);
        List<Turn> history = List.of(
                new Turn(Turn.USER, contexto), new Turn(Turn.ASSISTANT, larga),
                new Turn(Turn.USER, "¿y el café?"), new Turn(Turn.ASSISTANT, larga),
                new Turn(Turn.USER, "¿y la luz?"), new Turn(Turn.ASSISTANT, larga),
                new Turn(Turn.USER, "¿y la sal?"), new Turn(Turn.ASSISTANT, "corta"));

        List<Turn> compact = ChatService.compact(history);

        assertThat(compact).hasSize(8);
        assertThat(compact.get(0).content()).isEqualTo(contexto);                         // pregunta: entera
        assertThat(compact.get(1).content()).hasSize(ChatService.OLD_ANSWER_CHARS + 1);   // antigua: recortada (+ "…")
        assertThat(compact.get(3).content()).hasSize(ChatService.OLD_ANSWER_CHARS + 1);
        assertThat(compact.get(5).content()).isEqualTo(larga);                            // penúltima: entera
        assertThat(compact.get(7).content()).isEqualTo("corta");                          // última: entera
    }

    @Test
    void ignoraCitasQueNoExisten() {
        SortedMap<Integer, SearchResult> cited = ChatService.citedIn("Algo [7] y nada [0].", sources);

        assertThat(cited).isEmpty();
    }

    private static SearchResult chunk(long id) {
        return new SearchResult(id, "slug-" + id, "Título " + id, "https://example.org/" + id, "article", "texto " + id, 0);
    }

    @Test
    void theLastAnswerKeepsItsCitationsWithTheNewNumbers() {
        // La respuesta anterior citaba [2] y [5]; ahora son los fragmentos 1 y 2, y una búsqueda nueva sigue en el 3.
        SortedMap<Integer, SearchResult> previous = new TreeMap<>(Map.of(2, chunk(20), 5, chunk(50)));
        List<SearchResult> sources = new ArrayList<>();

        Map<Integer, Integer> renumber = ChatService.seedPrevious(previous, sources);

        assertThat(sources).extracting(SearchResult::chunkId).containsExactly(20L, 50L);
        assertThat(ChatService.renumbered("Leche con cereal [2]. Hígado [5][7]. Fruta [9].", renumber))
                .isEqualTo("Leche con cereal [1]. Hígado [2]. Fruta.");
        assertThat(ChatService.previousSourcesSection(sources)).contains("[1] ARTÍCULO: Título 20", "[2] ARTÍCULO: Título 50");
    }

    @Test
    void onlyTheFirstFragmentsOfALongAnswerAreShownAgain() {
        SortedMap<Integer, SearchResult> previous = new TreeMap<>();
        for (int n = 1; n <= 12; n++) {
            previous.put(n, chunk(n));
        }
        List<SearchResult> sources = new ArrayList<>();

        Map<Integer, Integer> renumber = ChatService.seedPrevious(previous, sources);

        assertThat(sources).hasSize(ChatService.MAX_PREVIOUS_SOURCES);
        assertThat(ChatService.renumbered("a [3] b [12]", renumber)).isEqualTo("a [3] b");
    }
}
