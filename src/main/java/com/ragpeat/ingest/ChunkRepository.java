package com.ragpeat.ingest;

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
}
