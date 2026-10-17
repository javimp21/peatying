package com.ragpeat.ingest;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mete un artículo en la base de datos: el documento y sus chunks. */
@Service
public class IndexService {

    private final Chunker chunker;
    private final ChunkRepository repository;

    public IndexService(Chunker chunker, ChunkRepository repository) {
        this.chunker = chunker;
        this.repository = repository;
    }

    /**
     * @Transactional: o se guarda TODO el artículo (documento + chunks) o NADA.
     * Si falla a mitad, Postgres deshace lo hecho (rollback) y no quedan artículos a medias.
     *
     * Borramos los chunks viejos y metemos los nuevos: así se puede relanzar las veces
     * que quieras (por ejemplo, si cambiamos el Chunker) sin duplicar nada.
     */
    @Transactional
    public int index(Article article) {
        long documentId = repository.upsertDocument(article);
        repository.deleteChunks(documentId);

        List<String> chunks = chunker.split(article.text());
        for (int i = 0; i < chunks.size(); i++) {
            repository.insertChunk(documentId, i, chunks.get(i));
        }
        return chunks.size();
    }
}
