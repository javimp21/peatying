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

        // Hay casi tres veces más texto de entrevistas que de artículos: si compitieran juntos, las
        // entrevistas taparían a los artículos, que son la fuente más fiable (texto escrito por Peat, literal).
        // Por eso cada tipo tiene sitio reservado: de 5 resultados, 3 de texto escrito por Peat y 2 entrevistas.
        // Lo escrito son los artículos y los libros: compiten juntos por esos 3 puestos.
        int interviews = limit * 2 / 5;

        return jdbc.sql("""
                WITH semantic AS (
                    -- <=> es la "distancia coseno" de pgvector: cuanto menor, más parecido el significado.
                    -- PARTITION BY: la posición se cuenta por separado dentro de lo escrito y de las entrevistas.
                    SELECT c.id, CASE WHEN d.source_type = 'interview' THEN 'interview' ELSE 'written' END AS source_type,
                           ROW_NUMBER() OVER (PARTITION BY d.source_type = 'interview'
                                              ORDER BY c.embedding <=> CAST(:vector AS vector)) AS rank
                    FROM chunks c JOIN documents d ON d.id = c.document_id
                    WHERE c.embedding IS NOT NULL
                ),
                keyword AS (
                    SELECT c.id, CASE WHEN d.source_type = 'interview' THEN 'interview' ELSE 'written' END AS source_type,
                           ROW_NUMBER() OVER (PARTITION BY d.source_type = 'interview'
                                              ORDER BY ts_rank(c.content_tsv, q) DESC) AS rank
                    FROM chunks c JOIN documents d ON d.id = c.document_id,
                         websearch_to_tsquery('english', :keywords) AS q
                    WHERE c.content_tsv @@ q        -- @@ = "el chunk contiene la búsqueda"
                ),
                fused AS (
                    SELECT COALESCE(s.id, k.id) AS id,
                           COALESCE(s.source_type, k.source_type) AS source_type,
                           COALESCE(1.0 / (60 + s.rank), 0) + COALESCE(1.0 / (60 + k.rank), 0) AS score
                    -- FULL OUTER JOIN: nos quedamos con los chunks que salen en cualquiera de las dos listas.
                    FROM (SELECT * FROM semantic WHERE rank <= :candidates) s
                    FULL OUTER JOIN (SELECT * FROM keyword WHERE rank <= :candidates) k ON k.id = s.id
                ),
                ranked AS (
                    SELECT *, ROW_NUMBER() OVER (PARTITION BY source_type ORDER BY score DESC) AS position
                    FROM fused
                ),
                chosen AS (
                    -- Primero los que caben en su cupo; si un tipo no llena el suyo (p. ej. no hay entrevistas),
                    -- el hueco lo ocupa el mejor del otro tipo.
                    SELECT * FROM ranked
                    ORDER BY position <= CASE WHEN source_type = 'interview' THEN :interviews ELSE :articles END DESC,
                             score DESC
                    LIMIT :limit
                )
                SELECT c.id AS chunk_id, d.slug, d.title, d.url, d.source_type, c.content, r.score
                FROM chosen r
                JOIN chunks c ON c.id = r.id
                JOIN documents d ON d.id = c.document_id
                ORDER BY r.score DESC
                """)
                .param("vector", queryVector)
                .param("keywords", keywordQuery)
                .param("candidates", CANDIDATES)
                .param("interviews", interviews)
                .param("articles", limit - interviews)
                .param("limit", limit)
                .query(SearchResult.class)
                .list();
    }
}
