package com.ragpeat.ingest;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mete un artículo o una entrevista en la base de datos: el documento y sus chunks. */
@Service
public class IndexService {

    private final Chunker chunker;
    private final InterviewChunker interviewChunker;
    private final ChunkRepository repository;

    public IndexService(Chunker chunker, InterviewChunker interviewChunker, ChunkRepository repository) {
        this.chunker = chunker;
        this.interviewChunker = interviewChunker;
        this.repository = repository;
    }

    /**
     * @Transactional: o se guarda TODO el artículo (documento + chunks) o NADA.
     * Si falla a mitad, Postgres deshace lo hecho (rollback) y no quedan artículos a medias.
     *
     * Si los chunks no han cambiado, no tocamos nada: así conservan su embedding y no
     * pagamos por recalcularlo. Si han cambiado, borramos los viejos y metemos los nuevos
     * (sin embedding; el comando "embed" los rellenará). Se puede relanzar las veces que quieras.
     *
     * @return cuántos chunks se han (re)escrito; 0 si el artículo no había cambiado.
     */
    @Transactional
    public int index(Article article) {
        return index(article.slug(), article.title(), article.url(), "article", chunker.split(article.text()));
    }

    @Transactional
    public int index(Interview interview) {
        return index(interview.slug(), interview.title(), interview.url(), "interview", interviewChunker.split(interview));
    }

    private int index(String slug, String title, String url, String sourceType, List<String> chunks) {
        // El título también cuenta como cambio: va delante de cada chunk al calcular su embedding.
        boolean sameTitle = repository.findTitle(slug).map(title::equals).orElse(false);
        long documentId = repository.upsertDocument(slug, title, url, sourceType);

        // List.equals compara elemento a elemento y en orden.
        if (sameTitle && chunks.equals(repository.findChunkContents(documentId))) {
            return 0;
        }

        repository.deleteChunks(documentId);
        for (int i = 0; i < chunks.size(); i++) {
            repository.insertChunk(documentId, i, chunks.get(i));
        }
        return chunks.size();
    }
}
