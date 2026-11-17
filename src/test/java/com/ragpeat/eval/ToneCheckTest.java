package com.ragpeat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToneCheckTest {

    @Test
    void detectaElArranqueNegativoSoloEnLaPrimeraFrase() {
        assertThat(ToneCheck.problems("Peat didn't discuss chia seeds specifically, but he did say [1].", false))
                .containsExactly(ToneCheck.NEGATIVE_OPENER);
        assertThat(ToneCheck.problems("Перт не писал о желудках индейки отдельно, но его идеи [3].", false))
                .containsExactly(ToneCheck.NEGATIVE_OPENER);
        assertThat(ToneCheck.problems("Peat no habló de la URSS como sistema, pero sí [2].", false))
                .containsExactly(ToneCheck.NEGATIVE_OPENER);
        // Más adelante en la respuesta sí puede decirlo.
        assertThat(ToneCheck.problems("Peat saw fruit as one of the best foods [1]. He didn't discuss chia.", false))
                .isEmpty();
        // Y si la pregunta es «¿habló Peat de X?», también al principio.
        assertThat(ToneCheck.problems("No, Peat didn't discuss tattoos.", true)).isEmpty();
    }

    @Test
    void detectaLosComentariosSobreLaPropiaDeduccion() {
        assertThat(ToneCheck.problems("Мясо умеренно [2]. Это моя интерпретация, а не его слова.", false))
                .containsExactly(ToneCheck.SELF_NOTE);
        assertThat(ToneCheck.problems("I'd rate it 8/10, but that's my estimate, not his.", false))
                .containsExactly(ToneCheck.SELF_NOTE);
        assertThat(ToneCheck.problems("Por su lógica, convendría comer fruta [1].", false)).isEmpty();
    }

    @Test
    void detectaQueAbraDandoLaRazon() {
        assertThat(ToneCheck.problems("Вы правы, я переборщил. Пит говорил [4].", false))
                .containsExactly(ToneCheck.CONCEDES);
        assertThat(ToneCheck.problems("Tienes razón: Peat criticaba los cereales [2].", false))
                .containsExactly(ToneCheck.CONCEDES);
        // «Right» en otro sentido no cuenta.
        assertThat(ToneCheck.problems("Right after waking, Peat liked orange juice [1].", false)).isEmpty();
    }
}
