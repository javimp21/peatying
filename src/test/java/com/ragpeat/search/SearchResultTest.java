package com.ragpeat.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchResultTest {

    private static SearchResult of(String type, boolean mixedAuthors) {
        return new SearchResult(1, "s", "t", "u", type, "c", 0, mixedAuthors);
    }

    @Test
    void mixedAuthorsNewsletterPageWarnsAboutOtherAuthors() {
        assertThat(of("newsletter", true).label())
                .startsWith("NEWSLETTER (página escaneada de la revista Townsend Letter")
                .contains("OTROS autores");
    }

    @Test
    void otherLabelsAreUnchanged() {
        assertThat(of("newsletter", false).label()).isEqualTo("NEWSLETTER");
        assertThat(of("article", true).label()).isEqualTo("ARTÍCULO");
        assertThat(of("book", false).label()).isEqualTo("LIBRO");
        assertThat(of("interview", false).label()).startsWith("ENTREVISTA");
    }
}
