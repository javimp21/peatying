package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.ragpeat.search.SearchResult;
import org.junit.jupiter.api.Test;

class FollowUpsTest {

    @Test
    void quitaVinetasYNumerosYSeQuedaConLasPreguntas() {
        assertThat(FollowUps.parse("- ¿Qué pensaba de la miel?\n* ¿Y de la fruta madura?"))
                .containsExactly("¿Qué pensaba de la miel?", "¿Y de la fruta madura?");
        assertThat(FollowUps.parse("1. What about honey?\n2) And ripe fruit?"))
                .containsExactly("What about honey?", "And ripe fruit?");
        assertThat(FollowUps.parse("• ¿Por qué evitaba el almidón?\n"))
                .containsExactly("¿Por qué evitaba el almidón?");
    }

    @Test
    void descartaLasLineasQueNoSonPregunta() {
        assertThat(FollowUps.parse("Here are two questions:\n¿Qué pensaba de la miel?\nNo question here.\nOther one?"))
                .containsExactly("¿Qué pensaba de la miel?", "Other one?");
        assertThat(FollowUps.parse("什么是蜂蜜？")).containsExactly("什么是蜂蜜？");
    }

    @Test
    void devuelveComoMuchoDos() {
        assertThat(FollowUps.parse("One?\nTwo?\nThree?\nFour?")).containsExactly("One?", "Two?");
    }

    @Test
    void descartaLasPreguntasDemasiadoLargas() {
        String tooLong = "Why " + "really ".repeat(20) + "?";
        assertThat(tooLong.length()).isGreaterThan(FollowUps.MAX_QUESTION_CHARS);
        assertThat(FollowUps.parse(tooLong + "\nShort one?")).containsExactly("Short one?");
    }

    @Test
    void unaRespuestaVaciaNoDaNada() {
        assertThat(FollowUps.parse("")).isEmpty();
        assertThat(FollowUps.parse("  \n \n")).isEmpty();
    }

    @Test
    void losFragmentosVanRecortadosEnTotal() {
        List<SearchResult> sources = List.of(
                new SearchResult(1, "a", "Title A", "u", "article", "x".repeat(4000), 0, false),
                new SearchResult(2, "b", "Title B", "u", "book", "y".repeat(4000), 0, false),
                new SearchResult(3, "c", "Title C", "u", "article", "z".repeat(4000), 0, false));

        String passages = FollowUps.passages(sources);

        assertThat(passages).hasSize(FollowUps.MAX_SOURCE_CHARS).contains("[Title A]", "[Title B]").doesNotContain("[Title C]");
    }
}
