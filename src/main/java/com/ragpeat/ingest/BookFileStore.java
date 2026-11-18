package com.ragpeat.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee los libros de Ray Peat (data/books/*.json) y sus newsletters (data/newsletters/issues/*.json), generados por scripts/extract_books.py a partir de los PDF.
 * Cada página impresa es un documento propio ("Generative Energy, p. 35"): así cada cita lleva su página.
 *
 * Los libros tienen derechos de autor: su texto solo lo usa el bot para entender y explicar con sus palabras.
 * Nunca se manda al navegador ni se publica (ver ChatController y app.js).
 *
 * Formato: {"slug": "generative-energy", "title": "Generative Energy", "url": "https://raypeat.com/products.shtml",
 *           "pages": [{"page": 35, "text": "..."}]}
 */
@Component
public class BookFileStore {

    private static final Path FOLDER = Path.of("data", "books");
    /** Los números de Ray Peat's Newsletter, mismo formato (scripts/extract_newsletters.py). */
    private static final Path NEWSLETTERS = Path.of("data", "newsletters", "issues");

    record BookFile(String slug, String title, String url, List<BookPage> pages) {}

    record BookPage(int page, String text) {}

    /** Una página de un libro o de una newsletter (type "book" o "newsletter"), lista para indexar. */
    public record Page(String slug, String title, String url, String type, String text) {}

    private final JsonMapper json;

    public BookFileStore(JsonMapper json) {
        this.json = json;
    }

    /** Vacía si no hay libros ni newsletters (p. ej. en local, sin los PDF). */
    public List<Page> loadAll() throws IOException {
        List<Page> pages = new ArrayList<>();
        load(FOLDER, "book", pages);
        load(NEWSLETTERS, "newsletter", pages);
        return pages;
    }

    private void load(Path folder, String type, List<Page> pages) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                BookFile book = json.readValue(file.toFile(), BookFile.class);
                for (BookPage p : book.pages()) {
                    // El ancla (#generative-energy-p35) hace única la URL de cada página: el enlace compartido
                    // busca el título por la URL. La página de raypeat.com es la de comprar los libros.
                    // Las newsletters ya llevan "newsletter-" en el slug del número.
                    String slug = "book".equals(type) ? "book-" + book.slug() : book.slug();
                    pages.add(new Page(
                            "%s-p%03d".formatted(slug, p.page()),
                            "%s, p. %d".formatted(book.title(), p.page()),
                            "%s#%s-p%d".formatted(book.url(), book.slug(), p.page()),
                            type,
                            p.text()));
                }
            }
        }
    }
}
