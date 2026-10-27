package com.ragpeat.eval;

import java.util.List;

/**
 * Una pregunta del banco de pruebas (eval/questions.json).
 *
 * type:
 *  - direct:    Peat trata el tema directamente. Debe citar alguno de los expectedArticles.
 *  - concept:   idea general de su teoría. También debe citar alguno de los expectedArticles.
 *  - absent:    Peat NO habla de eso en estos artículos. Debe reconocerlo, no inventárselo.
 *  - off_topic: no tiene nada que ver con Peat. No debe responder ni citar nada.
 *
 * bookTerms (opcional): una expresión regular en inglés con los términos del tema ("gelatin",
 * "coffee|caffeine"). Una página de libro citada también cuenta como fuente esperada si los menciona
 * al menos dos veces: los libros son texto de Peat, pero no se puede saber de antemano qué página citará.
 */
public record EvalQuestion(String id, String type, String question, List<String> expectedArticles, String bookTerms) {

    public EvalQuestion(String id, String type, String question, List<String> expectedArticles) {
        this(id, type, question, expectedArticles, null);
    }
}
