package com.ragpeat.ingest;

import java.util.ArrayList;
import java.util.List;

import com.ragpeat.ingest.Interview.Section;
import com.ragpeat.ingest.Interview.Turn;
import org.springframework.stereotype.Component;

/**
 * Parte una entrevista en chunks. Igual que con los artículos, ~1500 caracteres por chunk, pero
 * con tres reglas propias de una conversación:
 *
 *  1. Nunca mezcla secciones: cada sección de la transcripción es un tema (o la llamada de un oyente).
 *  2. Pregunta y respuesta van juntas. Si la respuesta de Peat no cabe y sigue en otro chunk, ese
 *     chunk empieza repitiendo la pregunta (recortada): sin ella, "Yes, but only in small amounts"
 *     no significa nada.
 *  3. Solo se guardan los chunks donde Peat dice algo de verdad: fuera presentaciones, anuncios,
 *     despedidas y charlas entre los presentadores.
 */
@Component
public class InterviewChunker {

    /** Menos de esto dicho por Peat (unas dos frases) no aporta nada que buscar. */
    static final int MIN_PEAT_CHARS = 200;
    /** La pregunta repetida como contexto se recorta: es contexto, no lo que queremos encontrar. */
    static final int MAX_CONTEXT_CHARS = 300;

    public List<String> split(Interview interview) {
        List<String> chunks = new ArrayList<>();
        for (Section section : interview.sections()) {
            chunks.addAll(split(section));
        }
        return chunks;
    }

    private List<String> split(Section section) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int peatChars = 0;
        Turn lastQuestion = null; // el último turno que no es de Peat: lo que se le acaba de preguntar

        for (Turn turn : section.turns()) {
            // El nombre de quien habla va al empezar su turno, y otra vez si el turno sigue en un chunk nuevo.
            boolean needsSpeaker = true;
            for (String paragraph : turn.text().split("\n\n")) {
                for (String piece : Chunker.fitToMax(paragraph)) {
                    if (!current.isEmpty() && current.length() + piece.length() > Chunker.MAX_CHARS) {
                        addIfPeatSpeaks(chunks, current, peatChars);
                        current.setLength(0);
                        peatChars = 0;
                        needsSpeaker = true;
                        if (turn.isPeat() && lastQuestion != null) {
                            current.append(lastQuestion.speaker()).append(": ").append(shorten(lastQuestion.text()));
                        }
                    }
                    if (!current.isEmpty()) {
                        current.append("\n\n");
                    }
                    if (needsSpeaker) {
                        current.append(turn.speaker()).append(": ");
                        needsSpeaker = false;
                    }
                    current.append(piece);
                    if (turn.isPeat()) {
                        peatChars += piece.length();
                    }
                }
            }
            if (!turn.isPeat()) {
                lastQuestion = turn;
            }
        }
        addIfPeatSpeaks(chunks, current, peatChars);
        return chunks;
    }

    private static void addIfPeatSpeaks(List<String> chunks, StringBuilder chunk, int peatChars) {
        if (peatChars >= MIN_PEAT_CHARS) {
            chunks.add(chunk.toString());
        }
    }

    private static String shorten(String text) {
        String oneLine = text.replace("\n\n", " ");
        return oneLine.length() <= MAX_CONTEXT_CHARS ? oneLine : oneLine.substring(0, MAX_CONTEXT_CHARS) + "…";
    }
}
