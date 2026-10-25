package com.ragpeat.ingest;

import java.util.List;

/**
 * Una entrevista a Ray Peat ya leída: de dónde viene, cómo se llama y lo que dijo cada uno.
 * Las transcripciones vienen divididas en secciones por tema ("## Cholesterol Myths"), y cada
 * sección es una sucesión de turnos de palabra.
 */
public record Interview(String slug, String title, String url, List<Section> sections) {

    public record Section(String heading, List<Turn> turns) {}

    /** Un turno de palabra: quién habla y qué dice (sus párrafos, separados por línea en blanco). */
    public record Turn(String speaker, String text) {

        public boolean isPeat() {
            return PEAT.equals(speaker);
        }
    }

    /** Nombre único para Peat: en los archivos aparece como "Dr. Raymond Peat", "Ray Peat"... */
    public static final String PEAT = "Ray Peat";
}
