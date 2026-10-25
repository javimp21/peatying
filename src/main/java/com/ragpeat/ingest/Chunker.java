package com.ragpeat.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Parte el texto de un artículo en trozos (chunks) de tamaño parecido.
 *
 * Regla: juntamos párrafos enteros hasta llegar a unos MAX_CHARS caracteres.
 * Así un trozo nunca corta una idea a mitad de párrafo.
 * Si un párrafo solo ya es demasiado largo, lo partimos por frases.
 */
@Component
public class Chunker {

    /** ~1500 caracteres ≈ 350 tokens: suficiente para una idea completa, sin mezclar demasiados temas. */
    static final int MAX_CHARS = 1500;

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : text.split("\n\n")) {
            for (String piece : fitToMax(paragraph)) {
                // Si añadir este trozo nos pasa del máximo, cerramos el chunk actual y empezamos otro.
                if (!current.isEmpty() && current.length() + piece.length() > MAX_CHARS) {
                    chunks.add(current.toString());
                    current.setLength(0); // vacía el StringBuilder para reutilizarlo
                }
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** Un párrafo corto se queda igual; uno largo se parte en grupos de frases que quepan en MAX_CHARS. */
    static List<String> fitToMax(String paragraph) {
        if (paragraph.length() <= MAX_CHARS) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // (?<=[.!?])\s+ : corta en los espacios que van justo DESPUÉS de un punto, ! o ?
        // ("lookbehind": mira el carácter anterior sin quitarlo del texto).
        for (String sentence : paragraph.split("(?<=[.!?])\\s+")) {
            if (!current.isEmpty() && current.length() + sentence.length() + 1 > MAX_CHARS) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }
}
