package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SortedMap;

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
}
