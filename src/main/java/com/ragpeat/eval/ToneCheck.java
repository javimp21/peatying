package com.ragpeat.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.ragpeat.chat.ToneFilter;

/**
 * Tres vicios de tono que el prompt prohíbe y que el modelo sigue cometiendo (revisión de conversaciones de
 * noviembre de 2026). Se detectan con expresiones regulares, sin juez, para contarlos en cada examen:
 *  - empezar diciendo que Peat no habló de algo («Peat didn't discuss X specifically, but…»);
 *  - comentar que lo dicho es una deducción propia («this is my interpretation, not his words»);
 *  - abrir dándole la razón a la persona («You're right», «Tienes razón», «Вы правы»).
 * En español, inglés y ruso, que son los idiomas de casi todas las preguntas. Las expresiones de los dos primeros
 * vicios están en ToneFilter, que también los quita de las respuestas en producción.
 */
public final class ToneCheck {

    public static final String NEGATIVE_OPENER = "empieza con «no habló de»";
    public static final String SELF_NOTE = "comenta que es deducción suya";
    public static final String CONCEDES = "abre dándole la razón";

    private static final Pattern CONCEDE = Pattern.compile(
            "^\\W*(you.re (absolutely )?right|you are right|good catch|tienes razón|tiene razón|tienes toda la razón"
                    + "|вы правы|ты прав)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ToneCheck() {}

    /** Los vicios de la respuesta. allowNegativeOpener: si la pregunta pide justo eso («¿habló Peat de X?»). */
    public static List<String> problems(String answer, boolean allowNegativeOpener) {
        List<String> found = new ArrayList<>();
        String text = answer.strip();
        if (!allowNegativeOpener && ToneFilter.NEGATIVE.matcher(firstSentence(text)).find()) {
            found.add(NEGATIVE_OPENER);
        }
        if (ToneFilter.SELF.matcher(text).find()) {
            found.add(SELF_NOTE);
        }
        if (CONCEDE.matcher(text).find()) {
            found.add(CONCEDES);
        }
        return found;
    }

    static String firstSentence(String text) {
        int end = text.length();
        for (String stop : List.of(". ", ".\n", "\n", ": ", "! ", "? ")) {
            int i = text.indexOf(stop);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return text.substring(0, end);
    }
}
