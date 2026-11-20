package com.ragpeat.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quita de la respuesta dos vicios de tono que el prompt prohíbe y que el modelo sigue cometiendo (revisión de
 * 1.481 respuestas reales): abrir con «Peat no habló de X, pero…» y comentar que lo dicho es una deducción propia.
 * ToneCheck (el examen) usa estas mismas expresiones, así que examen y producción coinciden.
 *
 * Trabaja por frases, y cada frase se decide solo con su propio texto y con si es la primera de la respuesta. Por eso
 * el resultado es el mismo escribas la respuesta de golpe (clean) o por trozos mientras llega (listener): lo que se
 * ve en la web es exactamente lo que se guarda.
 *
 *  1. Una frase que comenta que es deducción suya se borra entera (y la línea que quede vacía o con solo la viñeta).
 *  2. Si la primera frase dice que Peat no habló de algo y sigue con «, pero …», se queda lo de después del «pero».
 *     Sin esa conjunción no se toca: borrarla podría perder contenido.
 */
public final class ToneFilter {

    // Solo la primera frase: más adelante, decir que no trató algo puede ser legítimo.
    public static final Pattern NEGATIVE = Pattern.compile(
            "(didn.t|did not|never) (specifically )?(discuss|write|talk|mention|address|describe|cover|give|say)"
                    + "|no (habló|escribió|mencionó|trató|dio|describió)"
                    + "|не (писал|говорил|упоминал|обсуждал|описывал|разбирал)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern SELF = Pattern.compile(
            "my (own )?(interpretation|inference|reading|estimate|conclusion|extrapolation)"
                    + "|not his (words|verdict|approval|own)"
                    + "|mi (interpretación|deducción|valoración)|no (son )?sus palabras"
                    + "|(esto |eso )?(es|son) (una )?deducci[óo]n|como deducci[óo]n"
                    + "|(es )?una (estimaci[óo]n|extrapolaci[óo]n) m[íi]a|es m[íi]a,? (construida|hecha|a partir)"
                    + "|мо(я|й|ё) (интерпретация|вывод|оценка|прикидка|натяжка|экстраполяция)"
                    + "|не его (слова|одобрение|расч|оценка)|по моей логике",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CONJUNCTION = Pattern.compile("[,;] (pero|but|но) ",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ASKS_IF_HE_TALKED = Pattern.compile(
            ".*(habló|dijo algo|did (ray )?peat (ever )?(talk|discuss|mention|say|write)|говорил ли|писал ли).*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    private static final Pattern MARKER_ONLY = Pattern.compile("\\s*([-*•]|\\d+[.)])\\s*");

    private ToneFilter() {}

    /** Si la pregunta es «¿habló Peat de X?», empezar con «no» es la respuesta y no un vicio. */
    public static boolean asksIfHeTalked(String question) {
        return ASKS_IF_HE_TALKED.matcher(question).matches();
    }

    /** La respuesta sin los vicios. allowNegativeOpener: si la pregunta pide justo eso. */
    public static String clean(String answer, boolean allowNegativeOpener) {
        Filter filter = new Filter(allowNegativeOpener);
        return filter.push(answer) + filter.finish();
    }

    /**
     * Un listener que limpia el texto según llega: solo deja pasar frases completas. Al terminar la respuesta,
     * finish() suelta lo que quedaba. Si ChatService descarta el texto de un turno (va a buscar), empieza de cero.
     */
    public static Streaming listener(ChatListener target, boolean allowNegativeOpener) {
        return new Streaming(target, allowNegativeOpener);
    }

    public static final class Streaming implements ChatListener {

        private final ChatListener target;
        private final boolean allowNegativeOpener;
        private Filter filter;

        private Streaming(ChatListener target, boolean allowNegativeOpener) {
            this.target = target;
            this.allowNegativeOpener = allowNegativeOpener;
            this.filter = new Filter(allowNegativeOpener);
        }

        @Override
        public void onSearch(String query) {
            target.onSearch(query);
        }

        @Override
        public void onText(String delta) {
            forward(filter.push(delta));
        }

        @Override
        public void onDiscardText() {
            filter = new Filter(allowNegativeOpener);
            target.onDiscardText();
        }

        public void finish() {
            forward(filter.finish());
        }

        private void forward(String text) {
            if (!text.isEmpty()) {
                target.onText(text);
            }
        }
    }

    /** El estado de una respuesta que se va leyendo por trozos. */
    private static final class Filter {

        private final boolean allowNegativeOpener;
        private final StringBuilder pending = new StringBuilder(); // texto aún sin una frase completa
        private final StringBuilder hold = new StringBuilder();    // principio de línea (espacios o viñeta) sin contenido todavía
        private boolean first = true;
        private boolean lineHasContent;
        private int trailingNewlines = 2;                          // empieza como si hubiera línea en blanco: sin blancos iniciales

        Filter(boolean allowNegativeOpener) {
            this.allowNegativeOpener = allowNegativeOpener;
        }

        String push(String delta) {
            pending.append(delta);
            StringBuilder out = new StringBuilder();
            int end;
            while ((end = sentenceEnd(pending)) > 0) {
                sentence(pending.substring(0, end), out);
                pending.delete(0, end);
            }
            return out.toString();
        }

        String finish() {
            StringBuilder out = new StringBuilder();
            if (!pending.isEmpty()) {
                sentence(pending.toString(), out);
                pending.setLength(0);
            }
            hold.setLength(0);  // una viñeta o espacios sueltos al final no valen nada
            return out.toString();
        }

        /** Dónde acaba la primera frase del texto (con su espacio o salto de línea), o -1 si aún no está completa. */
        private static int sentenceEnd(CharSequence text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    return i + 1;
                }
                if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length()
                        && Character.isWhitespace(text.charAt(i + 1))) {
                    return i + 2;
                }
            }
            return -1;
        }

        private void sentence(String sentence, StringBuilder out) {
            boolean endsLine = sentence.endsWith("\n");
            String kept = decide(sentence);
            first = false;
            if (kept == null) {
                if (lineHasContent) {
                    if (endsLine) {
                        emit("\n", out);
                        lineHasContent = false;
                    }
                } else if (endsLine) {
                    hold.setLength(0);      // la línea entera era la frase borrada
                }
                return;
            }
            if (lineHasContent) {
                emit(kept, out);
                lineHasContent = !endsLine;
                return;
            }
            String line = hold + kept;
            String core = endsLine ? line.substring(0, line.length() - 1) : line;
            boolean blank = core.isBlank(), marker = MARKER_ONLY.matcher(core).matches();
            if (endsLine) {
                hold.setLength(0);
                if (blank) {
                    if (trailingNewlines < 2) {
                        emit("\n", out);
                    }
                } else if (!marker) {
                    emit(line, out);
                }
            } else if (blank || marker) {
                hold.setLength(0);
                hold.append(line);
            } else {
                hold.setLength(0);
                emit(line, out);
                lineHasContent = true;
            }
        }

        /** El texto que queda de la frase, o null si hay que borrarla entera. */
        private String decide(String sentence) {
            if (SELF.matcher(sentence).find()) {
                return null;
            }
            if (first && !allowNegativeOpener) {
                Matcher negative = NEGATIVE.matcher(sentence);
                if (negative.find()) {
                    Matcher conjunction = CONJUNCTION.matcher(sentence);
                    if (conjunction.find(negative.start())) {
                        return capitalized(sentence.substring(conjunction.end()));
                    }
                }
            }
            return sentence;
        }

        private static String capitalized(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetter(text.charAt(i))) {
                    return text.substring(0, i) + Character.toUpperCase(text.charAt(i)) + text.substring(i + 1);
                }
            }
            return text;
        }

        private void emit(String text, StringBuilder out) {
            out.append(text);
            int n = 0;
            while (n < text.length() && text.charAt(text.length() - 1 - n) == '\n') {
                n++;
            }
            trailingNewlines = n == text.length() ? trailingNewlines + n : n;
        }
    }
}
