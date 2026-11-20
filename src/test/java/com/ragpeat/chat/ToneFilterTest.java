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
    }

    @Test
    void pureNotesAreDroppedWholeButCommentariesWithContentAreNot() {
        String[] notes = {
                "Esta es una deducción mía, no una opinión que él diera sobre esos productos.",
                "Esto es una deducción a partir de sus ideas.",
                "Estas son deducciones mías a partir de sus ideas, no pautas que él diera.",
                "Eso es una deducción mía a partir de su idea.",
                "Esa frecuencia es una deducción mía, no algo que él propusiera.",
                "La escala de 0 a 10 es mía, construida con lo que decía sobre cada alimento.",
                "This is my own interpretation, not something he said.",
                "Это моя интерпретация, а не его слова.",
        };
        for (String note : notes) {
            assertThat(ToneFilter.clean("Antes [1].\n\n" + note + "\n\nDespués [2].", false))
                    .as(note).isEqualTo("Antes [1].\n\nDespués [2].");
        }
        String estimate = "Es una estimación de 2.300 kcal [1].";
        assertThat(ToneFilter.clean(estimate, false)).isEqualTo(estimate);
        String presents = "Esto es una deducción a partir de sus ideas: una leche de supermercado encajaría [6].";
        assertThat(ToneFilter.clean(presents, false)).isEqualTo(presents);
    }

    @Test
    void leadingClauseIsRemovedAndTheRestKept() {
        assertThat(ToneFilter.clean("Como deducción mía, una manzana con queso encajaría [1].", false))
                .isEqualTo("Una manzana con queso encajaría [1].");
        assertThat(ToneFilter.clean("Como deducción a partir de esas ideas, convendría:\n- Fruta [1].\n", false))
                .isEqualTo("Convendría:\n- Fruta [1].\n");
        assertThat(ToneFilter.clean("Como deducción mía, 300 g a la semana son unos 40 g al día [2].", false))
                .isEqualTo("300 g a la semana son unos 40 g al día [2].");
        assertThat(ToneFilter.clean("Intro [1].\n\nComo deducción a partir de esas ideas:\n- Fruta [1].\n", false))
                .isEqualTo("Intro [1].\n\n- Fruta [1].\n");
        assertThat(ToneFilter.clean("- **Como deducción mía, la avena** quedaría detrás [1].\n", false))
                .isEqualTo("- **La avena** quedaría detrás [1].\n");
    }

    @Test
    void embeddedClauseIsRemovedWithoutLeavingStrayCommas() {
        assertThat(ToneFilter.clean("Con esas ideas, y solo como deducción mía, el enfoque sería:\n- Fruta [1].\n", false))
                .isEqualTo("Con esas ideas, el enfoque sería:\n- Fruta [1].\n");
        assertThat(ToneFilter.clean("Una forma de ordenarlo, como deducción a partir de sus ideas:\n- Fruta [1].\n", false))
                .isEqualTo("Una forma de ordenarlo:\n- Fruta [1].\n");
        assertThat(ToneFilter.clean("Mi estimación, como deducción mía a partir de sus ideas, es de unas **2.300-2.500 kcal al día** [1].", false))
                .isEqualTo("Mi estimación es de unas **2.300-2.500 kcal al día** [1].");
    }

    @Test
    void trailingClauseIsRemovedKeepingTheFinalPunctuation() {
        assertThat(ToneFilter.clean("Por eso convendría un ejercicio moderado [2], aunque esto es una deducción mía a partir de lo que dice.", false))
                .isEqualTo("Por eso convendría un ejercicio moderado [2].");
        assertThat(ToneFilter.clean("Le pasaría también a ella, pero eso es una deducción mía.", false))
                .isEqualTo("Le pasaría también a ella.");
    }

    @Test
    void otherSelfNotesAreLeftAlone() {
        String[] untouched = {
                "So for steamed milk, my reading of his ideas is that gentle heating costs little nutritionally [1].",
                "Como Peat no habló de esa marca concreta, esto es una deducción a partir de sus ideas: una leche de Hacendado encajaría [6].",
                "Por mi deducción, la leche entera encajaría mejor [2].",
                "По моей логике, это так.",
                "Una vez dicho, convendría una sesión corta de luz roja [3].",
                "Es una deducción aplicarla a la garganta.",
                "Es una estimación mía, y es menos de lo que calculé antes.",
        };
        for (String text : untouched) {
            assertThat(ToneFilter.clean(text, true)).as(text).isEqualTo(text);
        }
    }

    @Test
    void negativeOpenerFollowedByAnAffirmationIsLeftAlone() {
        String[] untouched = {
                "Peat no habló de los abdominales en concreto, pero sí de la frecuencia del ejercicio.",
                "Peat no habló del boniato en particular, pero sí dijo algo sobre él.",
                "Peat didn't discuss tattoos, but yes, he talked about skin [1].",
                "Пэт не писал об этом, но да, он говорил о коже [1].",
        };
        for (String text : untouched) {
            assertThat(ToneFilter.clean(text, false)).as(text).isEqualTo(text);
        }
        assertThat(ToneFilter.clean("Peat no dio cantidades de pollo ni de patata para una cena concreta, pero sus ideas apuntan a un plato con patata [1].", false))
                .isEqualTo("Sus ideas apuntan a un plato con patata [1].");
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
        listener.onText("Peat no habló de X, pero sus ideas apuntan a Y [1].");
        listener.finish();
        assertThat(events).containsExactly("text:Voy a buscar. ", "discard", "text:Sus ideas apuntan a Y [1].");
    }
}
