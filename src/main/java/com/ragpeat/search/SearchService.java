package com.ragpeat.search;

import java.util.List;

import com.ragpeat.embedding.PgVector;
import com.ragpeat.embedding.VoyageClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Búsqueda híbrida: combina dos búsquedas y mezcla sus resultados.
 *
 *  1. Semántica (por significado): compara el embedding de la pregunta con el de cada chunk.
 *     Encuentra textos que dicen lo mismo con otras palabras, incluso en otro idioma.
 *  2. Por palabras (full-text de Postgres): encuentra los chunks que contienen las palabras exactas.
 *     Útil para nombres y términos concretos ("Budd", "pregnenolone").
 *
 * Para mezclarlas usamos Reciprocal Rank Fusion (RRF): cada chunk suma 1/(60 + posición)
 * por cada lista en la que aparece. Lo que sale arriba en las dos listas gana.
 */
@Service
public class SearchService {

    /** Cuántos candidatos sacamos de cada búsqueda antes de mezclar. */
    private static final int CANDIDATES = 20;

    private final JdbcClient jdbc;
    private final VoyageClient voyage;

    public SearchService(JdbcClient jdbc, VoyageClient voyage) {
        this.jdbc = jdbc;
        this.voyage = voyage;
    }

    /**
     * @param query           la pregunta tal cual (cualquier idioma): para la búsqueda semántica.
     * @param englishKeywords palabras clave EN INGLÉS para la búsqueda por palabras, o null para no usarla.
     *                        No usamos la pregunta en español: buscaría palabras como "de" o "la"
     *                        y encontraría ruido ("Bungenberg de Jong"). Más adelante será el modelo
     *                        quien escriba estas palabras clave en inglés.
     */
    public List<SearchResult> search(String query, String englishKeywords, int limit) {
        String queryVector = PgVector.toLiteral(voyage.embedQuery(query));

        // websearch_to_tsquery exige TODAS las palabras por defecto, demasiado estricto.
        // Con "or" basta con que aparezca alguna; ts_rank puntúa más alto a los chunks que tienen más.
        // Si no hay palabras clave, pasamos "": no encuentra nada y solo cuenta la búsqueda semántica.
        String keywordQuery = englishKeywords == null || englishKeywords.isBlank()
                ? ""
                : String.join(" or ", englishKeywords.trim().split("\\s+"));

        return jdbc.sql("""
                WITH semantic AS (
                    -- <=> es la "distancia coseno" de pgvector: cuanto menor, más parecido el significado.
                    SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> CAST(:vector AS vector)) AS rank
                    FROM chunks
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(:vector AS vector)
                    LIMIT :candidates
                ),
                keyword AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(content_tsv, q) DESC) AS rank
                    FROM chunks, websearch_to_tsquery('english', :keywords) AS q
                    WHERE content_tsv @@ q          -- @@ = "el chunk contiene la búsqueda"
                    ORDER BY ts_rank(content_tsv, q) DESC
                    LIMIT :candidates
                )
                SELECT c.id AS chunk_id, d.title, d.url, c.content,
                       COALESCE(1.0 / (60 + s.rank), 0) + COALESCE(1.0 / (60 + k.rank), 0) AS score
                -- FULL OUTER JOIN: nos quedamos con los chunks que salen en cualquiera de las dos listas.
                FROM semantic s
                FULL OUTER JOIN keyword k ON k.id = s.id
                JOIN chunks c ON c.id = COALESCE(s.id, k.id)
                JOIN documents d ON d.id = c.document_id
                ORDER BY score DESC
                LIMIT :limit
                """)
                .param("vector", queryVector)
                .param("keywords", keywordQuery)
                .param("candidates", CANDIDATES)
                .param("limit", limit)
                .query(SearchResult.class)
                .list();
    }
}
