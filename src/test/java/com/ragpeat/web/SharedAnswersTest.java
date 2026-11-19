package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragpeat.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * El flujo de compartir una respuesta contra la base de datos real: guardar la copia, enseñarla en /r/{id}
 * y negarse cuando el id secreto de la conversación no coincide.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SharedAnswersTest {

    private static final String KNOWN_URL = "https://raypeat.com/articles/it-sugar-issues.html";
    private static final Pattern LINK = Pattern.compile("\"url\"\\s*:\\s*\"(/r/[a-zA-Z0-9]{10})\"");

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    MockMvc mvc;
    UUID conversationId;
    long messageId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.sql("INSERT INTO documents (slug, title, url, source_type) VALUES ('it-sugar-issues', 'Sugar issues', :url, 'article')")
                .param("url", KNOWN_URL).update();
        conversationId = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id) VALUES (:id)").param("id", conversationId).update();
        jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content) VALUES (:c, 'user', 'What did Peat say about sugar?')")
                .param("c", conversationId).update();
        messageId = jdbc.sql("""
                INSERT INTO chat_messages (conversation_id, role, content)
                VALUES (:c, 'assistant', 'He defended sugar as a good fuel [1]. Some claim more [2].') RETURNING id
                """).param("c", conversationId).query(Long.class).single();
    }

    @Test
    void sharedAnswerIsPublishedWithOnlyVerifiedSources() throws Exception {
        String response = mvc.perform(post("/api/share").contentType(MediaType.APPLICATION_JSON)
                        .content(request(conversationId, "es")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher link = LINK.matcher(response);
        assertThat(link.find()).as(response).isTrue();

        String html = mvc.perform(get(link.group(1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Sugar issues").doesNotContain("example.com");
    }

    @Test
    void otherConversationCannotGetTheExistingLink() throws Exception {
        mvc.perform(post("/api/share").contentType(MediaType.APPLICATION_JSON).content(request(conversationId, "es")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/share").contentType(MediaType.APPLICATION_JSON).content(request(UUID.randomUUID(), "es")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownIdIsNotFound() throws Exception {
        mvc.perform(get("/r/abcdefghij")).andExpect(status().isNotFound());
    }

    private String request(UUID conversation, String lang) {
        return """
                {"conversationId": "%s", "messageId": %d, "lang": "%s",
                 "sources": [{"number": 1, "url": "%s"}, {"number": 2, "url": "https://example.com/not-in-corpus"}]}
                """.formatted(conversation, messageId, lang, KNOWN_URL);
    }
}
