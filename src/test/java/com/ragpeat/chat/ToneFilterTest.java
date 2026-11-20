package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ToneFilterTest {

    @Test
    void negativeOpenerWithConjunctionKeepsWhatComesAfter() {
        assertThat(ToneFilter.clean("Peat no habló de los kebabs, pero sus ideas apuntan a X [1]. Resto.", false))
                .isEqualTo("Sus ideas apuntan a X [1]. Resto.");
        assertThat(ToneFilter.clean("Peat didn't discuss kettlebells specifically, but he described heavy lifting as stress [1]. More.", false))
                .isEqualTo("He described heavy lifting as stress [1]. More.");
        assertThat(ToneFilter.clean("Пэт не писал о X отдельно, но его идеи таковы [1].", false))
                .isEqualTo("Его идеи таковы [1].");
    }

    @Test
    void negativeOpenerWithoutConjunctionStaysAndSoDoesTheAllowedOne() {
        String noConjunction = "Peat no habló de los kebabs. Sí dijo que la carne es pesada [1].";
        assertThat(ToneFilter.clean(noConjunction, false)).isEqualTo(noConjunction);
        String opener = "Peat no habló de los kebabs, pero sus ideas apuntan a X [1].";
        assertThat(ToneFilter.clean(opener, true)).isEqualTo(opener);
    }

    @Test
    void onlyTheFirstSentenceCanBeRewritten() {
        String text = "Peat saw fruit as great [1]. He didn't discuss chia, but he liked seeds less [2].";
        assertThat(ToneFilter.clean(text, false)).isEqualTo(text);
    }

    @Test
    void asksIfHeTalkedDetectsTheQuestion() {
        assertThat(ToneFilter.asksIfHeTalked("¿Habló Peat de los tatuajes?")).isTrue();
        assertThat(ToneFilter.asksIfHeTalked("Did Peat ever discuss tattoos?")).isTrue();
        assertThat(ToneFilter.asksIfHeTalked("¿Qué opinaba del azúcar?")).isFalse();
    }

    @Test
    void selfCommentAtTheEndOfAParagraphIsRemovedWithoutDoubleBlankLine() {
        String text = "Primero [1].\n\nEsto es una deducción a partir de sus ideas, no algo que él observara.\n\nSegundo [2].";
        assertThat(ToneFilter.clean(text, false)).isEqualTo("Primero [1].\n\nSegundo [2].");
    }

    @Test
    void selfSentenceInTheMiddleOfALineOnlyLosesThatSentence() {
        assertThat(ToneFilter.clean("Comer fruta [1]. Esto es una deducción mía. Con proteína [2].\nFin.", false))
                .isEqualTo("Comer fruta [1]. Con proteína [2].\nFin.");
        assertThat(ToneFilter.clean("Comer fruta [1]. Esto es una deducción mía.\nFin.", false))
                .isEqualTo("Comer fruta [1]. \nFin.");
    }

    @Test
    void bulletWhoseWholeContentIsASelfCommentDisappears() {
        String text = "Ideas:\n- Fruta [1].\n- Esto es una deducción mía.\n- Leche [2].\n1. Otra.\n2. Es una estimación mía.\n";
        assertThat(ToneFilter.clean(text, false)).isEqualTo("Ideas:\n- Fruta [1].\n- Leche [2].\n1. Otra.\n");
    }

    @Test
    void russianSelfNoteIsRemovedButAttributionStays() {
        assertThat(ToneFilter.clean("Он ценил сахар [1]. Это моя прикидка. По его логике, фрукты полезны [2].", false))
                .isEqualTo("Он ценил сахар [1]. По его логике, фрукты полезны [2].");
        assertThat(ToneFilter.clean("Он ценил сахар [1]. По моей логике, это так.", false)).isEqualTo("Он ценил сахар [1]. ");
    }

    @Test
    void citationsDoNotBreakSentenceSplitting() {
        String text = "Peat decía que sí [3]. Y que no [4]. Más 0.95 l de leche [1].";
        assertThat(ToneFilter.clean(text, false)).isEqualTo(text);
        assertThat(ToneFilter.clean("Peat no habló de X, pero decía Y [3]. Z [4].", false)).isEqualTo("Decía Y [3]. Z [4].");
    }

    private static final String LONG = """
            Peat no habló de las lentejas en concreto, pero desaconsejaba las legumbres [1]. Tienen almidón y fibra [2].

            Qué hacer:
            - Cocinarlas mucho [3]. Esto es una deducción a partir de sus ideas.
            - Esto es una deducción mía.
            - Mejor fruta con proteína [4].
            1. Primero la fruta.
            2. Como deducción, luego el queso.

            Esto es una deducción, no algo que él dijera.

            Fin de la respuesta [5].""";

    @Test
    void streamingGivesTheSameTextForAnySplit() {
        String expected = ToneFilter.clean(LONG, false);
        assertThat(expected).startsWith("Desaconsejaba las legumbres [1]. ").doesNotContain("deducci");
        Random random = new Random(42);
        for (int attempt = 0; attempt < 200; attempt++) {
            List<String> forwarded = new ArrayList<>();
            ToneFilter.Streaming listener = ToneFilter.listener(new ChatListener() {
                @Override
                public void onText(String delta) {
                    forwarded.add(delta);
                }
            }, false);
            for (int i = 0; i < LONG.length(); ) {
                int end = Math.min(LONG.length(), i + 1 + random.nextInt(12));
                listener.onText(LONG.substring(i, end));
                i = end;
            }
            listener.finish();
            assertThat(String.join("", forwarded)).as("attempt " + attempt).isEqualTo(expected);
        }
    }

    @Test
    void streamingForwardsOnlyCompleteSentencesAndSearchesPassThrough() {
        List<String> events = new ArrayList<>();
        ToneFilter.Streaming listener = ToneFilter.listener(new ChatListener() {
            @Override
            public void onText(String delta) {
                events.add("text:" + delta);
            }

            @Override
            public void onSearch(String query) {
                events.add("search:" + query);
            }
        }, false);
        listener.onSearch("q");
        listener.onText("Frase uno. Frase do");
        assertThat(events).containsExactly("search:q", "text:Frase uno. ");
        listener.onText("s.");
        listener.finish();
        assertThat(events).containsExactly("search:q", "text:Frase uno. ", "text:Frase dos.");
    }

    @Test
    void discardingTextResetsTheFilterAndIsForwarded() {
        List<String> events = new ArrayList<>();
        ToneFilter.Streaming listener = ToneFilter.listener(new ChatListener() {
            @Override
            public void onText(String delta) {
                events.add("text:" + delta);
            }

            @Override
            public void onDiscardText() {
                events.add("discard");
            }
        }, false);
        listener.onText("Voy a buscar. Peat no habló de");
        listener.onDiscardText();
        listener.onText("Peat no habló de X, pero sí de Y [1].");
        listener.finish();
        assertThat(events).containsExactly("text:Voy a buscar. ", "discard", "text:Sí de Y [1].");
    }
}
