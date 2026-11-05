package com.ragpeat.topics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ragpeat.topics.TopicsCommand.Page;
import com.ragpeat.topics.TopicsCommand.Topic;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class TopicPageRendererTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static Page page(String slug, String title) {
        return new Page(slug, slug, title, "¿Pregunta?", "Descripción", "Intro", List.of(), List.of(), List.of());
    }

    @Test
    void laPaginaEnlazaASusTemasRelacionados_yCuentaLaVisita() {
        String html = new TopicPageRenderer(json).page(page("azucar", "Ray Peat y el azúcar"), TopicPageRenderer.ES, null,
                List.of(page("dieta", "La dieta de Ray Peat")));

        assertThat(html).contains("<h2>Temas relacionados</h2>")
                .contains("<a href=\"/temas/dieta\">La dieta de Ray Peat</a>")
                .contains("<script src=\"/visit.js\"></script>");
    }

    @Test
    void sinRelacionados_noSalePuntoVacio() {
        String html = new TopicPageRenderer(json).page(page("azucar", "Ray Peat y el azúcar"), TopicPageRenderer.EN, null, List.of());

        assertThat(html).doesNotContain("Related topics");
    }

    @Test
    void cadaTemaEnlazaATemasQueExisten_yNingunoSeQuedaSinEnlacesEntrantes() {
        List<Topic> topics = json.readValue(Path.of("topics", "topics.json").toFile(), new TypeReference<List<Topic>>() {});
        Set<String> slugs = new HashSet<>(topics.stream().map(Topic::slug).toList());
        Set<String> linked = new HashSet<>();

        for (Topic topic : topics) {
            assertThat(topic.related()).as(topic.slug()).isNotEmpty().doesNotContain(topic.slug()).doesNotHaveDuplicates();
            assertThat(slugs).as(topic.slug()).containsAll(topic.related());
            linked.addAll(topic.related());
        }

        assertThat(linked).isEqualTo(slugs);
    }
}
