package com.ragpeat.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tacha los datos que identifican a una persona antes de guardar un mensaje en la base de datos.
 * Pensado sobre todo para analíticas pegadas tal cual: se quedan los resultados (colesterol, TSH...),
 * pero no el documento, la fecha de nacimiento, el nombre del médico ni el número de historia.
 *
 * Es una red, no una garantía: un nombre escrito en una frase normal ("me llamo Ana") no se detecta.
 */
public final class PersonalData {

    static final String MASK = "[dato personal]";

    /** Lo que se refiere al documento o al expediente, y no a la salud: "DNI:", "HC:", "Fecha Nac.:", "Médico:"... */
    private static final String LABELS = String.join("|",
            "fecha\\s+de\\s+nacimiento", "fecha\\s+nac(?:imiento|\\.)?", "f\\.\\s*nac(?:imiento|\\.)?", "nacimiento", "edad",
            "historia\\s+cl[ií]nica", "(?:n[º°o]|nro|n[uú]m(?:ero)?)\\.?\\s*(?:de\\s+)?(?:historia|afiliado|informe|documento|protocolo|paciente|socio|orden|muestra)",
            "dni", "nie", "nif", "cuil", "cuit", "pasaporte", "documento", "n?hc", "cip", "sip", "nss",
            "tarjeta\\s+sanitaria", "seguridad\\s+social", "obra\\s+social", "afiliado", "mutua",
            "paciente", "nombre(?:\\s+y\\s+apellidos?)?", "apellidos?",
            "m[eé]dic[oa](?:\\s+solicitante)?", "doctora?", "dra?\\.?", "profesional", "solicitante",
            "domicilio", "direcci[oó]n", "tel[eé]fono", "tel", "m[oó]vil", "celular", "e-?mail", "correo");

    /** La etiqueta tiene que ir seguida de dos puntos: así "mi médico me dijo" no se toca. */
    private static final Pattern LABEL = Pattern.compile(
            "(?<![\\p{L}\\d])(?:" + LABELS + ")\\.?\\s*:\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Datos que se reconocen solos, sin etiqueta delante. */
    private static final Pattern UNLABELED = Pattern.compile(String.join("|",
            "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+",              // email
            "(?<![\\w])\\d{8}[A-HJ-NP-TV-Z](?![\\w])",        // DNI español
            "(?<![\\w])[XYZ]\\d{7}[A-Z](?![\\w])",            // NIE
            "\\+\\d[\\d\\s().-]{7,}\\d",                      // teléfono con prefijo internacional
            "(?<![\\d.,])\\d{8,}(?![\\d.,])"));               // números largos: documentos, códigos de barras, teléfonos

    /** Hasta dónde llega el valor de una etiqueta: fin de línea, tabulador o dos espacios (columnas). */
    private static final Pattern VALUE_END = Pattern.compile("\\R|\\t| {2,}");
    private static final int MAX_VALUE = 60;

    private PersonalData() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return UNLABELED.matcher(redactLabeled(text)).replaceAll(Matcher.quoteReplacement(MASK));
    }

    /** "DNI: 12345678" -> "DNI: [dato personal]". Deja la etiqueta, para que se entienda qué había. */
    private static String redactLabeled(String text) {
        StringBuilder out = new StringBuilder();
        Matcher label = LABEL.matcher(text);
        int copied = 0;
        while (copied < text.length() && label.find(copied)) {
            int valueStart = label.end();
            int valueEnd = Math.min(text.length(), valueStart + MAX_VALUE);
            Matcher end = VALUE_END.matcher(text).region(valueStart, valueEnd);
            if (end.find()) {
                valueEnd = end.start();
            }
            // Si en la misma columna empieza otra etiqueta ("HC: 123 Fecha Nac.: ..."), el valor acaba ahí.
            Matcher next = LABEL.matcher(text).region(valueStart + 1, valueEnd);
            if (valueStart + 1 < valueEnd && next.find()) {
                valueEnd = next.start();
            }
            out.append(text, copied, valueStart);
            if (!text.substring(valueStart, valueEnd).isBlank()) {
                out.append(MASK);
                // Conservamos el espacio que separa del siguiente dato.
                if (valueEnd < text.length() && Character.isWhitespace(text.charAt(valueEnd - 1))) {
                    out.append(' ');
                }
            }
            copied = valueEnd;
        }
        return out.append(text, copied, text.length()).toString();
    }
}
