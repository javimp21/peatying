package com.ragpeat.embedding;

/**
 * pgvector entiende los vectores escritos como texto: "[0.12,-0.5,0.33,...]".
 * Esta clase solo hace esa conversión, para usarla en el SQL con CAST(:vector AS vector).
 */
public final class PgVector {

    private PgVector() {} // clase de utilidades: no se crean objetos de ella

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
