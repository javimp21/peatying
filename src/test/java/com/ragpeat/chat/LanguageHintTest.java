package com.ragpeat.chat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué preguntas pasan por la detección de idioma (preguntas reales de la web o parecidas). */
class LanguageHintTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Test olmaksızın tiroidin yavaş çalıştığı nasıl anlaşılabilir? | true
            Co Peat sądził o kawie?                                        | true
            Was hält Ray Peat von Milch?                                   | true
            O que Peat pensava sobre o açúcar?                             | true
            Почему Пит любил сахар?                                        | true
            Cómo hacer zanahoria rayada, y cuántos carbs recomienda Ray peat al día? | false
            ¿Qué pensaba del pomelo?                                       | false
            What did he think of serotonin?                                | false
            Hcg for men?                                                   | false
            Şeker                                                          | false
            Груша                                                          | true
            Кока кола                                                      | true
            """)
    void asksOnlyForSentencesWithLettersOutsideEnglishAndSpanish(String question, boolean asks) {
        assertThat(LanguageHint.ownText(question) != null).isEqualTo(asks);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Sobre esta parte de tu respuesta anterior: «Peat hält Milch für gut»\\n\\nno entiendo esto, me lo explicas? | false
            About this part of your previous answer: “Peat said sugar helps”\\n\\nCo to dokładnie znaczy? | true
            """)
    void ignoresTheQuotedPartOfThePreviousAnswer(String question, boolean asks) {
        assertThat(LanguageHint.ownText(question.replace("\\n", "\n")) != null).isEqualTo(asks);
    }
}
