package com.ragpeat.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.ragpeat.embedding.PgVector;
import com.ragpeat.embedding.VoyageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Cuántos candidatos sacamos de cada búsqueda antes de mezclar. */
    private static final int CANDIDATES = 20;
    /** Cuántos candidatos de la búsqueda híbrida reordena el reranker. */
    static final int RERANK_POOL = 30;

    private final JdbcClient jdbc;
    private final VoyageClient voyage;
    private final boolean rerank;

    public SearchService(JdbcClient jdbc, VoyageClient voyage, @Value("${search.rerank:true}") boolean rerank) {
        this.jdbc = jdbc;
        this.voyage = voyage;
        this.rerank = rerank;
    }

    /**
     * @param query           la pregunta tal cual (cualquier idioma): para la búsqueda semántica.
     * @param englishKeywords palabras clave EN INGLÉS para la búsqueda por palabras, o null para no usarla.
     *                        No usamos la pregunta en español: buscaría palabras como "de" o "la"
     *                        y encontraría ruido ("Bungenberg de Jong"). Más adelante será el modelo
     *                        quien escriba estas palabras clave en inglés.
     */
    /** Fragmentos concretos por su id (los que citó una respuesta anterior). Los que ya no existan no salen. */
    public List<SearchResult> byIds(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT c.id AS chunk_id, d.slug, d.title, d.url, d.source_type, c.content, 0 AS score
                FROM chunks c JOIN documents d ON d.id = c.document_id
                WHERE c.id IN (:ids)
                """)
                .param("ids", chunkIds)
                .query(SearchResult.class)
                .list();
    }

    /** Los fragmentos de un documento por su slug, en orden (para reproducir conversaciones en el examen). */
    public List<SearchResult> ofDocument(String slug) {
        return jdbc.sql("""
                SELECT c.id AS chunk_id, d.slug, d.title, d.url, d.source_type, c.content, 0 AS score
                FROM chunks c JOIN documents d ON d.id = c.document_id
                WHERE d.slug = :slug
                ORDER BY c.id
                """)
                .param("slug", slug)
                .query(SearchResult.class)
                .list();
    }

    /**
     * La búsqueda híbrida trae candidatos de más (RERANK_POOL) y el reranker de Voyage los ordena leyendo la
     * consulta y cada texto juntos. Sin él, una consulta como "thyroid in newborns" trae lo más general del
     * tiroides: los embeddings ven sobre todo el tema, y lo que hace concreta la pregunta pesa poco.
     * Si el reranker falla, se usa el orden de la búsqueda híbrida: nunca deja al bot sin resultados.
     */
    public List<SearchResult> search(String query, String englishKeywords, int limit) {
        if (!rerank) {
            return hybrid(query, englishKeywords, limit);
        }
        List<SearchResult> pool = hybrid(query, englishKeywords, Math.max(limit, RERANK_POOL));
        try {
            String rerankQuery = englishKeywords == null || englishKeywords.isBlank() ? query
                    : query + " (" + englishKeywords + ")";
            double[] scores = voyage.rerank(rerankQuery, pool.stream().map(SearchResult::content).toList());
            List<SearchResult> reranked = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                SearchResult r = pool.get(i);
                reranked.add(new SearchResult(r.chunkId(), r.slug(), r.title(), r.url(), r.sourceType(), r.content(),
                        scores[i]));
            }
            reranked.sort(Comparator.comparingDouble(SearchResult::score).reversed());
            return withQuotas(reranked, limit);
        } catch (RuntimeException e) {
            log.warn("El reranker ha fallado; uso el orden de la búsqueda híbrida: {}", e.toString());
            return withQuotas(pool, limit);
        }
    }

    /** Cuántas entrevistas caben en {@code limit} resultados (el resto, texto escrito por Peat). */
    static int interviewQuota(int limit) {
        return limit * 2 / 5;
    }

    /**
     * Los {@code limit} mejores de una lista ya ordenada, con el mismo reparto que la búsqueda híbrida: 3 de cada
     * 5 de texto escrito y 2 de entrevistas. Si un tipo no llena su cupo, el hueco es para el mejor del otro.
     */
    static List<SearchResult> withQuotas(List<SearchResult> sorted, int limit) {
        int interviews = interviewQuota(limit), written = limit - interviews;
        List<SearchResult> chosen = new ArrayList<>(), rest = new ArrayList<>();
        int i = 0, w = 0;
        for (SearchResult r : sorted) {
            if (r.isInterview() ? i < interviews : w < written) {
                chosen.add(r);
                if (r.isInterview()) i++; else w++;
            } else {
                rest.add(r);
            }
        }
        for (SearchResult r : rest) {
            if (chosen.size() >= limit) break;
            chosen.add(r);
        }
        List<SearchResult> result = new ArrayList<>(chosen.subList(0, Math.min(limit, chosen.size())));
        result.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return result;
    }

    private List<SearchResult> hybrid(String query, String englishKeywords, int limit) {
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
        int interviews = interviewQuota(limit);

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
