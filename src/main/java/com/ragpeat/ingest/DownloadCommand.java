package com.ragpeat.ingest;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Se ejecuta al arrancar la aplicación, pero solo hace algo si le pasas el argumento "download":
 *   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=download"
 *
 * Es reanudable: si un artículo ya está en disco, lo salta. Si se corta a la mitad,
 * vuelves a lanzarlo y sigue por donde iba.
 */
@Component
public class DownloadCommand implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DownloadCommand.class);
    private static final long PAUSE_MS = 1500; // pausa entre peticiones para no cargar su servidor

    private final RayPeatScraper scraper;
    private final ArticleFileStore store;

    // Inyección por constructor: Spring crea el scraper y el store y nos los pasa.
    public DownloadCommand(RayPeatScraper scraper, ArticleFileStore store) {
        this.scraper = scraper;
        this.store = store;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!List.of(args).contains("download")) {
            return;
        }

        List<String> urls = scraper.fetchArticleUrls();
        log.info("Encontrados {} artículos en el índice", urls.size());

        int saved = 0, skipped = 0, failed = 0;
        for (String url : urls) {
            String slug = new Article(url, "", "").slug();
            if (store.exists(slug)) {
                skipped++;
                continue;
            }
            // Un artículo que falla no debe parar los demás: lo apuntamos y seguimos.
            try {
                Article article = scraper.fetchArticle(url);
                store.save(article);
                saved++;
                log.info("[{}/{}] {} ({} caracteres)", saved + skipped + failed, urls.size(),
                        article.title(), article.text().length());
            } catch (Exception e) {
                failed++;
                log.warn("Error con {}: {}", url, e.getMessage());
            }
            Thread.sleep(PAUSE_MS);
        }

        log.info("Fin. Guardados: {}, ya existían: {}, con error: {}", saved, skipped, failed);
    }
}
