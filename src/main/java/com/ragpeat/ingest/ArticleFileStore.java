package com.ragpeat.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Guarda cada artículo en un archivo de texto: data/articles/<slug>.txt
 *
 * Formato (fácil de abrir y revisar a mano):
 *   línea 1: título
 *   línea 2: URL
 *   línea 3: vacía
 *   resto:   texto del artículo
 */
@Component
public class ArticleFileStore {

    private final Path folder = Path.of("data", "articles");

    public boolean exists(String slug) {
        return Files.exists(fileFor(slug));
    }

    public void save(Article article) throws IOException {
        Files.createDirectories(folder);
        String content = article.title() + "\n" + article.url() + "\n\n" + article.text() + "\n";
        Files.writeString(fileFor(article.slug()), content, StandardCharsets.UTF_8);
    }

    /** Lee todos los artículos guardados (el camino inverso de save). */
    public List<Article> loadAll() throws IOException {
        List<Article> articles = new ArrayList<>();
        // try-with-resources: Files.list abre la carpeta y hay que cerrarla; el try lo hace solo al terminar.
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".txt")).sorted().toList()) {
                // split("\n", 4): como mucho 4 partes -> título, url, línea vacía, y TODO el resto junto.
                String[] parts = Files.readString(file, StandardCharsets.UTF_8).split("\n", 4);
                articles.add(new Article(parts[1], parts[0], parts[3].strip()));
            }
        }
        return articles;
    }

    private Path fileFor(String slug) {
        return folder.resolve(slug + ".txt");
    }
}
