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
 *  2. Un chunk que empieza por un corte lleva delante el intercambio anterior: la última pregunta
 *     (recortada) y el principio de la última respuesta de Peat, bajo «[Earlier in this interview]».
 *     El bot solo ve el chunk, y «Would it bring stress hormones down in people too?» no dice de qué
 *     hablan: el tema (la pregnenolona) estaba en el chunk anterior. La pregunta es el último turno de
 *     otro que tenga cuerpo (se saltan «Wow.», «Okay.»); la respuesta es el turno de Peat más reciente,
 *     aunque el corte caiga en mitad de él. El primer chunk de cada sección no lleva nada.
 *  3. Solo se guardan los chunks donde Peat dice algo de verdad: fuera presentaciones, anuncios,
 *     despedidas y charlas entre los presentadores. Lo que Peat dice en el contexto no cuenta.
 */
@Component
public class InterviewChunker {

    /** Menos de esto dicho por Peat (unas dos frases) no aporta nada que buscar. */
    static final int MIN_PEAT_CHARS = 200;
    /** Cada parte del contexto se recorta: es contexto, no lo que queremos encontrar. */
    static final int MAX_CONTEXT_CHARS = 300;
    /** Una pregunta más corta («Wow.», «Right.») no dice de qué se habla: se busca la anterior. */
    static final int MIN_QUESTION_CHARS = 40;
    static final String CONTEXT_HEADER = "[Earlier in this interview]";

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
        List<Turn> done = new ArrayList<>(); // los turnos anteriores al que se está troceando

        for (Turn turn : section.turns()) {
            // El nombre de quien habla va al empezar su turno, y otra vez si el turno sigue en un chunk nuevo.
            boolean needsSpeaker = true;
            for (String paragraph : turn.text().split("\n\n")) {
                for (String piece : Chunker.fitToMax(paragraph)) {
                    if (!current.isEmpty() && current.length() + piece.length() > Chunker.MAX_CHARS) {
                        addIfPeatSpeaks(chunks, current, peatChars);
                        current.setLength(0);
                        peatChars = 0;
                        current.append(previousExchange(done, turn, !needsSpeaker));
                        needsSpeaker = true;
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
            done.add(turn);
        }
        addIfPeatSpeaks(chunks, current, peatChars);
        return chunks;
    }

    /**
     * El contexto de un chunk que empieza por un corte dentro de {@code turn}, o "" si aún no se ha dicho nada.
     * La respuesta es el turno de Peat más reciente (este mismo, si es suyo y ya había empezado en el chunk anterior); la pregunta, el último turno de otro con
     * cuerpo anterior a esa respuesta. Sin respuesta (Peat aún no ha hablado), solo la pregunta.
     */
    private static String previousExchange(List<Turn> done, Turn turn, boolean turnStarted) {
        Turn answer = turn.isPeat() && turnStarted ? turn : null;
        int before = done.size();
        if (answer == null) {
            for (int i = done.size() - 1; i >= 0 && answer == null; i--) {
                if (done.get(i).isPeat()) {
                    answer = done.get(i);
                    before = i;
                }
            }
        }
        Turn question = null;
        for (int i = before - 1; i >= 0 && question == null; i--) {
            Turn candidate = done.get(i);
            if (!candidate.isPeat() && candidate.text().length() >= MIN_QUESTION_CHARS) {
                question = candidate;
            }
        }
        StringBuilder context = new StringBuilder();
        if (question != null) {
            context.append(question.speaker()).append(": ").append(shorten(question.text()));
        }
        if (answer != null) {
            context.append(context.isEmpty() ? "" : "\n").append(answer.speaker()).append(": ").append(shorten(answer.text()));
        }
        return context.isEmpty() ? "" : CONTEXT_HEADER + "\n" + context;
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
