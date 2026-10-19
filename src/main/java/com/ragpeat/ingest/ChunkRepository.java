package com.ragpeat.ingest;

import java.util.List;
import java.util.Optional;

import com.ragpeat.embedding.PgVector;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Todo el SQL de documentos y chunks está aquí.
 * Usamos JdbcClient (SQL escrito a mano) en vez de JPA/Hibernate: con pocas tablas
 * y consultas especiales (vectores, búsqueda de texto) es más claro ver el SQL tal cual.
 */
@Repository
public class ChunkRepository {

    private final JdbcClient jdbc;

    public ChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta el documento, o lo actualiza si ya existía ese slug ("upsert"). Devuelve su id.
     * ON CONFLICT (slug) DO UPDATE: si ya hay una fila con ese slug, en vez de fallar la actualiza.
     * RETURNING id: devuelve el id de la fila, sea nueva o actualizada.
     */
    public long upsertDocument(Article article) {
        return jdbc.sql("""
                INSERT INTO documents (slug, title, url)
                VALUES (:slug, :title, :url)
                ON CONFLICT (slug) DO UPDATE SET title = EXCLUDED.title, url = EXCLUDED.url
                RETURNING id
                """)
                .param("slug", article.slug())
                .param("title", article.title())
                .param("url", article.url())
                .query(Long.class)
                .single();
    }

    /** El título guardado de un documento, o vacío si aún no existe. */
    public Optional<String> findTitle(String slug) {
        return jdbc.sql("SELECT title FROM documents WHERE slug = :slug")
                .param("slug", slug)
                .query(String.class)
                .optional();
    }

    /** El texto de los chunks de un documento, en orden. */
    public List<String> findChunkContents(long documentId) {
        return jdbc.sql("SELECT content FROM chunks WHERE document_id = :id ORDER BY position")
                .param("id", documentId)
                .query(String.class)
                .list();
    }

    public void deleteChunks(long documentId) {
        jdbc.sql("DELETE FROM chunks WHERE document_id = :id")
                .param("id", documentId)
                .update();
    }

    public void insertChunk(long documentId, int position, String content) {
        jdbc.sql("INSERT INTO chunks (document_id, position, content) VALUES (:doc, :pos, :content)")
                .param("doc", documentId)
                .param("pos", position)
                .param("content", content)
                .update();
    }

    /** Un chunk que aún no tiene embedding, con el título de su artículo. */
    public record PendingChunk(long id, String title, String content) {}

    public List<PendingChunk> findWithoutEmbedding(int limit) {
        return jdbc.sql("""
                SELECT c.id, d.title, c.content
                FROM chunks c JOIN documents d ON d.id = c.document_id
                WHERE c.embedding IS NULL
                ORDER BY c.id
                LIMIT :limit
                """)
                .param("limit", limit)
                // query(PendingChunk.class): Spring crea un PendingChunk por fila,
                // emparejando las columnas id/title/content con los campos del record.
                .query(PendingChunk.class)
                .list();
    }

    public void updateEmbedding(long chunkId, float[] embedding) {
        jdbc.sql("UPDATE chunks SET embedding = CAST(:embedding AS vector) WHERE id = :id")
                .param("embedding", PgVector.toLiteral(embedding))
                .param("id", chunkId)
                .update();
    }
}
