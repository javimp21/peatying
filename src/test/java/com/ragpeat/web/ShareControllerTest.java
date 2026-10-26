package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.ragpeat.web.ShareController.Source;
import org.junit.jupiter.api.Test;

class ShareControllerTest {

    private final Map<Integer, Source> sources = Map.of(1, new Source(1, "Sugar issues", "https://raypeat.com/x", "article"));

    @Test
    void escapesHtmlFromTheAnswer() {
        String html = ShareController.markdown("Hola <script>alert(1)</script> **negrita** [1]", sources);
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;", "<strong>negrita</strong>",
                "<a class=\"topic-cite\" href=\"#fuente-1\">1</a>");
    }

    @Test
    void dropsCitationsWithoutAVerifiedSource() {
        assertThat(ShareController.markdown("Algo [7].", sources)).isEqualTo("<p>Algo .</p>\n");
    }

    @Test
    void rendersLists() {
        assertThat(ShareController.markdown("Intro:\n\n- uno [1]\n- dos", sources))
                .contains("<p>Intro:</p>", "<ul>", "<li>uno <a", "<li>dos</li>", "</ul>");
    }

    @Test
    void keepsOnlyTheQuestionWhenReplyingToAFragment() {
        assertThat(ShareController.withoutQuote("Sobre esta parte de tu respuesta anterior: «la prolactina»\n\n¿Y eso?"))
                .isEqualTo("¿Y eso?");
        assertThat(ShareController.withoutQuote("¿Qué pensaba del azúcar?")).isEqualTo("¿Qué pensaba del azúcar?");
    }

    @Test
    void plainTextForThePreview() {
        assertThat(ShareController.plain("**Azúcar:** protege [1].\n- punto [2]")).isEqualTo("Azúcar: protege. punto");
    }
}
