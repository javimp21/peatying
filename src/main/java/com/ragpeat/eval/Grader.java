package com.ragpeat.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ragpeat.chat.ChatAnswer;
import com.ragpeat.ingest.Article;

/**
 * Corrige una respuesta del bot con reglas automáticas (sin usar otro modelo).
 * Son comprobaciones sencillas pero objetivas: siempre dan el mismo resultado.
 */
public class Grader {

    /**
     * Texto entre comillas rectas o tipográficas: "..." o “...”.
     * Sin saltos de línea ni "[" dentro: una cita real no contiene un [3]. Si no exigimos esto, con
     * comillas mal emparejadas la regex junta el final de una cita con el principio de la siguiente.
     */
    private static final Pattern QUOTE = Pattern.compile("[\"“«]([^\"”»\\[\\n]{15,400})[\"”»]");
    // También «...»: el prompt las reserva para el español, pero si el bot mete ahí una frase en
    // inglés, es una cita literal y hay que comprobarla igual (looksEnglish descarta las españolas).

    private static final Set<String> ENGLISH_WORDS = Set.of("the", "of", "and", "to", "is", "that", "in", "it", "are", "by", "with");
    private static final Set<String> SPANISH_WORDS = Set.of("el", "la", "los", "las", "de", "del", "al", "que", "y",
            "en", "es", "un", "una", "por", "para", "con", "se", "lo", "su", "sus", "no", "como", "pero");

    /** Frases con las que el bot reconoce que algo no está en los artículos. */
    private static final Pattern ADMITS_ABSENCE = Pattern.compile(
            "(?i)no (lo )?(menciona|habla|aparece|aparecen|trata|dice|encontr|encuentro|se menciona|hay)");

    /** El texto de todos los artículos, normalizado, para comprobar que las citas literales existen. */
    private final String corpus;

    public Grader(List<Article> articles) {
        this.corpus = normalize(articles.stream().map(Article::text).collect(Collectors.joining(" ")));
    }

    public record Grade(boolean passed, boolean sourceHit, int quotes, List<String> invalidQuotes, String reason) {}

    public Grade grade(EvalQuestion q, ChatAnswer answer) {
        Set<String> citedSlugs = answer.citedSources().values().stream()
                .map(source -> new Article(source.url(), "", "").slug())
                .collect(Collectors.toSet());
        boolean sourceHit = q.expectedArticles().stream().anyMatch(citedSlugs::contains);

        List<String> quotes = quotesIn(answer.text());
        List<String> invalid = quotes.stream().filter(quote -> !existsInCorpus(quote)).toList();

        String reason = switch (q.type()) {
            case "direct", "concept" -> {
                if (citedSlugs.isEmpty()) yield "no cita ninguna fuente";
                if (!sourceHit) yield "no cita ninguno de los artículos esperados (cita " + citedSlugs + ")";
                yield null;
            }
            case "absent" -> ADMITS_ABSENCE.matcher(answer.text()).find()
                    ? null : "no reconoce que Peat no habla de esto";
            case "off_topic" -> citedSlugs.isEmpty() ? null : "responde citando fuentes a algo que no es de Peat";
            default -> throw new IllegalArgumentException("Tipo desconocido: " + q.type());
        };
        // Una cita literal que no existe en ningún artículo es inventada: suspende siempre.
        if (reason == null && !invalid.isEmpty()) {
            reason = "cita literal que no existe en los artículos";
        }
        return new Grade(reason == null, sourceHit, quotes.size(), invalid, reason);
    }

    /** Saca los textos entre comillas. Si llevan "..." en medio, cada trozo se comprueba por separado. */
    static List<String> quotesIn(String text) {
        List<String> quotes = new ArrayList<>();
        Matcher matcher = QUOTE.matcher(text);
        while (matcher.find()) {
            for (String piece : matcher.group(1).split("\\.\\.\\.|…")) {
                if (piece.strip().length() >= 15 && looksEnglish(piece)) {
                    quotes.add(piece.strip());
                }
            }
        }
        return quotes;
    }

    /**
     * Solo comprobamos citas en inglés: una frase en español entre comillas es una traducción
     * o un término, no una cita literal, y nunca va a aparecer tal cual en los artículos.
     * Truco sencillo: es español si tiene tildes/ñ/¿¡ o más palabras comunes españolas que inglesas.
     */
    static boolean looksEnglish(String text) {
        if (text.matches("(?s).*[áéíóúñÁÉÍÓÚÑ¿¡].*")) {
            return false;
        }
        long english = 0, spanish = 0;
        for (String word : text.toLowerCase().split("[^\\p{L}']+")) {
            if (ENGLISH_WORDS.contains(word)) english++;
            if (SPANISH_WORDS.contains(word)) spanish++;
        }
        return spanish <= english;
    }

    private boolean existsInCorpus(String quote) {
        return corpus.contains(normalize(quote));
    }

    /**
     * Minúsculas, apóstrofes unificados, sin comillas dobles y espacios simples.
     * Así "it’s" y "it's" cuentan igual, y una cita no falla porque el original
     * tuviera una palabra entre comillas y el bot las quitara.
     */
    static String normalize(String text) {
        return text.toLowerCase()
                .replace('’', '\'').replace('‘', '\'')
                .replaceAll("[\"“”]", "")
                .replaceAll("\\s+", " ");
    }
}
