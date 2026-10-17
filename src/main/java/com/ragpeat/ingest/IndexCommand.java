package com.ragpeat.ingest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Lee los artículos de data/articles, los trocea y los guarda en Postgres.
 * Solo actúa con el argumento "index":
 *   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=index"
 */
@Component
public class IndexCommand implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexCommand.class);

    private final ArticleFileStore store;
    private final IndexService indexService;

    public IndexCommand(ArticleFileStore store, IndexService indexService) {
        this.store = store;
        this.indexService = indexService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!List.of(args).contains("index")) {
            return;
        }

        List<Article> articles = store.loadAll();
        int totalChunks = 0;
        for (Article article : articles) {
            totalChunks += indexService.index(article);
        }
        log.info("Indexados {} artículos en {} chunks", articles.size(), totalChunks);
    }
}
