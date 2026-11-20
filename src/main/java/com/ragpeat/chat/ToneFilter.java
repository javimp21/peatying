package com.ragpeat.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quita de la respuesta dos vicios de tono que el prompt prohíbe y que el modelo sigue cometiendo (revisión de
 * 1.481 respuestas reales): abrir con «Peat no habló de X, pero…» y comentar que lo dicho es una deducción propia.
 * ToneCheck (el examen) usa el mismo patrón de la apertura negativa; para contar las notas de deducción tiene el suyo,
 * más amplio, porque contar no borra nada.
 *
 * Trabaja por frases, y cada frase se decide solo con su propio texto y con si es la primera de la respuesta. Por eso
 * el resultado es el mismo escribas la respuesta de golpe (clean) o por trozos mientras llega (listener): lo que se
 * ve en la web es exactamente lo que se guarda.
 *
 * Solo corta en unas pocas formas precisas; si una frase tiene el vicio pero no encaja en ninguna, se deja tal cual,
 * porque lo que no se sabe cortar sin perder contenido (la recomendación, la cifra pedida) no se toca:
 *  1. La frase que es solo la nota («Esto es una deducción mía a partir de sus ideas.») se borra entera, y la línea
 *     que quede vacía o con solo la viñeta.
 *  2. «Como deducción mía, …» al principio: se quita la cláusula y se sigue con lo demás.
 *  3. «…, como deducción mía, …» en medio: se quita la cláusula.
 *  4. «…, aunque esto es una deducción mía.» al final: se quita la cláusula.
 *  5. Si la primera frase dice que Peat no habló de algo y sigue con «, pero …», se queda lo de después del «pero»
 *     (salvo si lo que sigue es «sí…»: «no habló de X, pero sí de Y» no se puede recortar). Sin conjunción no se toca.
 */
public final class ToneFilter {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // Solo la primera frase: más adelante, decir que no trató algo puede ser legítimo.
    public static final Pattern NEGATIVE = Pattern.compile(
            "(didn.t|did not|never) (specifically )?(discuss|write|talk|mention|address|describe|cover|give|say)"
                    + "|no (habló|escribió|mencionó|trató|dio|describió)"
                    + "|не (писал|говорил|упоминал|обсуждал|описывал|разбирал)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String START = "^[\\s*•\\-]*";

    private static final Pattern ONLY_A_NOTE = Pattern.compile(
            START + "(((esto|eso|esta|estas|esa|esas|este|estos|es|son|aun así,? esto)\\s+(es|son)?\\s*(una |unas )?"
                    + "((deducci[óo]n|deducciones)(?=\\s*(?:m[íi]a|suya|a partir|sobre|de |[,.!?]|$))"
                    + "|(estimaci[óo]n|extrapolaci[óo]n) m[íi]a(?=\\s*(?:[.!?]|$|, no ))))"
                    + "|((this|that|these) (is|are) (my (own )?(interpretation|inference|reading|estimate|extrapolation|deduction)"
                    + "|an? (inference|deduction|extrapolation)))"
                    + "|((это|эта|этот) (моя|мой|моё) (интерпретация|вывод|оценка|прикидка|экстраполяция))"
                    + "|(la escala .* es m[íi]a)"
                    + "|(esa (frecuencia|cifra|lista|escala) es (una deducci[óo]n )?m[íi]a))",
            FLAGS);

    private static final Pattern LEADING_CLAUSE = Pattern.compile(
            "^(\\W*)(como deducci[óo]n( m[íi]a| a partir de (sus|sus propias|esas?|estas?|lo que [^,:]*) ?"
                    + "(ideas?|lo que dice|que dijo)?)?|por deducci[óo]n m[íi]a|y solo como deducci[óo]n m[íi]a)[ \\t]*[,:][ \\t]*",
            FLAGS);

    private static final String DEDUCTION = "como deducci[óo]n (?:m[íi]a(?: a partir de (?:sus|esas?|estas?) ideas)?"
            + "|a partir de (?:sus|esas?|estas?) ideas)";

    // «Mi estimación, como deducción mía a partir de sus ideas, es de…»: la cláusula va entre comas y se va con ellas.
    private static final Pattern CLAUSE_BETWEEN_COMMAS = Pattern.compile(
            ", (?:y solo )?" + DEDUCTION + ",(?= (es|son|sería|serían|convendría|encajaría|podría|debería|puede)\\b)", FLAGS);

    private static final Pattern EMBEDDED_CLAUSE = Pattern.compile(", (?:y solo )?" + DEDUCTION + "\\s*([,:])", FLAGS);

    private static final Pattern TRAILING_CLAUSE = Pattern.compile(
            "[,;]\\s*(aunque|pero)\\s+(esto |eso )?(es|son) (una )?deducci[óo]n( m[íi]a| suya)?( a partir de [^.!?]*)?"
                    + "(, no [^.!?]*)?(?=[.!?]\\s*$)",
            FLAGS);

    private static final Pattern AFFIRMS_AFTER_BUT = Pattern.compile("(sí|si|yes|he did|did|да)\\b", FLAGS);

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
            if (kept != null && endsLine && !kept.endsWith("\n")) {
                kept += "\n";       // una edición nunca puede comerse el salto de línea que cierra la frase
            }
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
            // Con «:» la frase presenta algo que viene detrás: no es solo una nota.
            if (ONLY_A_NOTE.matcher(sentence).find() && !sentence.contains(":")) {
                return null;
            }
            String text = sentence;
            Matcher leading = LEADING_CLAUSE.matcher(text);
            if (leading.find()) {
                text = leading.group(1) + capitalized(text.substring(leading.end()));
            }
            text = CLAUSE_BETWEEN_COMMAS.matcher(text).replaceAll("");
            text = EMBEDDED_CLAUSE.matcher(text).replaceAll("$1");
            text = TRAILING_CLAUSE.matcher(text).replaceAll("");
            if (first && !allowNegativeOpener) {
                Matcher negative = NEGATIVE.matcher(text);
                if (negative.find()) {
                    Matcher conjunction = CONJUNCTION.matcher(text);
                    if (conjunction.find(negative.start())
                            && !AFFIRMS_AFTER_BUT.matcher(text).region(conjunction.end(), text.length()).lookingAt()) {
                        return capitalized(text.substring(conjunction.end()));
                    }
                }
            }
            return text;
        }

        private static String capitalized(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isDigit(c)) {
                    return text;        // «300 g…»: la unidad que sigue no se toca
                }
                if (Character.isLetter(c)) {
                    return text.substring(0, i) + Character.toUpperCase(c) + text.substring(i + 1);
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
