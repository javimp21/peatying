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
 */
public record EvalQuestion(String id, String type, String question, List<String> expectedArticles) {}
