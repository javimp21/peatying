package com.ragpeat.ingest;

import java.util.List;

import com.ragpeat.embedding.VoyageClient;
import com.ragpeat.ingest.ChunkRepository.PendingChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Calcula el embedding de cada chunk que aún no lo tenga y lo guarda en Postgres.
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar embed
 *
 * Reanudable: solo procesa chunks con embedding NULL. Si se corta, se relanza y sigue.
 */
@Component
public class EmbedCommand implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbedCommand.class);

    /**
     * Chunks por petición. Voyage acepta hasta 1.000 textos y 320K tokens por petición;
     * 128 chunks son ~45K tokens: lejos del límite y pocas peticiones (~13 en total).
     */
    private static final int BATCH_SIZE = 128;

    private final ChunkRepository repository;
    private final VoyageClient voyage;

    public EmbedCommand(ChunkRepository repository, VoyageClient voyage) {
        this.repository = repository;
        this.voyage = voyage;
    }

    @Override
    public void run(String... args) {
        if (!List.of(args).contains("embed")) {
            return;
        }

        int done = 0;
        List<PendingChunk> batch;
        // Pedimos lotes de chunks sin embedding hasta que no quede ninguno.
        while (!(batch = repository.findWithoutEmbedding(BATCH_SIZE)).isEmpty()) {
            // Al chunk le ponemos delante el título del artículo: así el vector sabe de qué
            // trata el texto aunque el trozo no lo diga (p. ej. un párrafo que solo dice "this hormone").
            List<String> texts = batch.stream()
                    .map(c -> "Ray Peat, \"" + c.title() + "\"\n\n" + c.content())
                    .toList();

            List<float[]> embeddings = voyage.embedDocuments(texts);

            for (int i = 0; i < batch.size(); i++) {
                repository.updateEmbedding(batch.get(i).id(), embeddings.get(i));
            }
            done += batch.size();
            log.info("Embeddings guardados: {}", done);
        }
        log.info("Fin. Total en esta ejecución: {}", done);
    }
}
