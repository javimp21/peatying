package com.ragpeat.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import com.ragpeat.TestcontainersConfiguration;
import com.ragpeat.embedding.PgVector;
import com.ragpeat.embedding.VoyageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * El SQL de la búsqueda híbrida contra un Postgres con pgvector de verdad: los tests unitarios no lo ejecutan.
 *
 * Los vectores se construyen a mano en los tres primeros ejes (e0, e1, e2) para saber de antemano el orden por
 * parecido con la pregunta, que es el eje e0: cuanto más se aleja un vector de e0, más abajo queda.
 */
@SpringBootTest(properties = "search.rerank=false")
@Import(TestcontainersConfiguration.class)
@Transactional
class HybridSearchTest {

    @Autowired
    SearchService search;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    VoyageClient voyage;

    @BeforeEach
    void queryIsE0() {
        when(voyage.embedQuery(anyString())).thenReturn(vector(1, 0, 0));
    }

    @Test
    void reservesSlotsForWrittenTextAndInterviews() {
        articles();
        interviews();

        List<SearchResult> results = search.search("q", null, 5);

        assertThat(results).hasSize(5);
        assertThat(slugs(results, false)).containsExactlyInAnyOrder("it-a1", "it-a2", "it-a3");
        assertThat(slugs(results, true)).containsExactlyInAnyOrder("it-i1", "it-i2");
    }

    @Test
    void keywordSearchPromotesAFarAwayChunk() {
        articles();
        interviews();

        List<SearchResult> results = search.search("q", "pregnenolone", 5);

        assertThat(results.getFirst().slug()).isEqualTo("it-a4");
    }

    @Test
    void writtenTextFillsTheInterviewSlotsWhenThereAreNoInterviews() {
        articles();

        List<SearchResult> results = search.search("q", null, 5);

        assertThat(results).extracting(SearchResult::slug)
                .containsExactlyInAnyOrder("it-a1", "it-a2", "it-a3", "it-a4", "it-a5");
    }

    @Test
    void mixedAuthorsFlagComesFromTheDocument() {
        articles();
        jdbc.sql("UPDATE documents SET mixed_authors = true WHERE slug = 'it-a1'").update();

        List<SearchResult> results = search.search("q", null, 5);

        assertThat(results).filteredOn(SearchResult::mixedAuthors).extracting(SearchResult::slug).containsExactly("it-a1");
        assertThat(search.ofDocument("it-a1")).extracting(SearchResult::mixedAuthors).containsExactly(true);
        long chunkId = results.stream().filter(r -> r.slug().equals("it-a1")).findFirst().orElseThrow().chunkId();
        assertThat(search.byIds(List.of(chunkId))).extracting(SearchResult::mixedAuthors).containsExactly(true);
    }

    private void articles() {
        insert("it-a1", "article", "Thyroid and metabolism in the body.", vector(1, 0, 0));
        insert("it-a2", "article", "Sugar and energy production in cells.", vector(1, 0.5f, 0));
        insert("it-a3", "article", "Calcium and milk in the diet.", vector(1, 1, 0));
        insert("it-a5", "article", "Salt and aldosterone in blood pressure.", vector(1, 2, 0));
        insert("it-a4", "article", "Pregnenolone as a protective steroid.", vector(0, 0, 1));
    }

    private void interviews() {
        insert("it-i1", "interview", "We talked about thyroid and temperature.", vector(1, 0.2f, 0));
        insert("it-i2", "interview", "We talked about sugar and fruit.", vector(1, 0.8f, 0));
        insert("it-i3", "interview", "We talked about milk and cheese.", vector(1, 1.5f, 0));
    }

    private void insert(String slug, String sourceType, String content, float[] embedding) {
        long documentId = jdbc.sql("""
                INSERT INTO documents (slug, title, url, source_type) VALUES (:slug, :title, :url, :type) RETURNING id
                """)
                .param("slug", slug).param("title", "Title " + slug).param("url", "https://example.org/" + slug)
                .param("type", sourceType).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO chunks (document_id, position, content, embedding)
                VALUES (:document, 0, :content, CAST(:vector AS vector))
                """)
                .param("document", documentId).param("content", content)
                .param("vector", PgVector.toLiteral(embedding)).update();
    }

    private static List<String> slugs(List<SearchResult> results, boolean interviews) {
        return results.stream().filter(r -> r.isInterview() == interviews).map(SearchResult::slug).toList();
    }

    /** Un vector de 1024 dimensiones con solo los tres primeros ejes, normalizado a longitud 1. */
    private static float[] vector(float e0, float e1, float e2) {
        float[] v = new float[VoyageClient.DIMENSIONS];
        double norm = Math.sqrt(e0 * e0 + e1 * e1 + e2 * e2);
        v[0] = (float) (e0 / norm);
        v[1] = (float) (e1 / norm);
        v[2] = (float) (e2 / norm);
        return v;
    }
}
