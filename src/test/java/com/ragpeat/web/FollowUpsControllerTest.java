package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ragpeat.TestcontainersConfiguration;
import com.ragpeat.chat.ConversationRepository;
import com.ragpeat.chat.ConversationRepository.AnswerMetrics;
import com.ragpeat.chat.FollowUps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Las preguntas de seguimiento contra la base de datos real, con el modelo simulado (nada llama a Claude). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class FollowUpsControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConversationRepository conversations;

    @MockitoBean
    FollowUps followUps;

    MockMvc mvc;
    UUID conversationId;
    long chunkId;
    long firstAnswer;
    long secondAnswer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long documentId = jdbc.sql("INSERT INTO documents (slug, title, url, source_type) VALUES ('it-honey', 'Honey', 'https://example.org/honey', 'article') RETURNING id")
                .query(Long.class).single();
        chunkId = jdbc.sql("INSERT INTO chunks (document_id, position, content) VALUES (:d, 0, 'Honey has fructose and glucose.') RETURNING id")
                .param("d", documentId).query(Long.class).single();
        conversationId = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id) VALUES (:id)").param("id", conversationId).update();
        firstAnswer = exchange(conversationId, "Why sugar?", "Because [1].", "1:" + chunkId);
        secondAnswer = exchange(conversationId, "And honey?", "Also [1].", "1:" + chunkId);
        when(followUps.suggest(anyString(), anyString(), anyCollection())).thenReturn(List.of("What about fruit?", "And milk?"));
    }

    @Test
    void generatesOnceAndStoresTheSuggestions() throws Exception {
        call(conversationId, firstAnswer).andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", contains("What about fruit?", "And milk?")));
        call(conversationId, firstAnswer).andExpect(status().isOk())
                .andExpect(jsonPath("$.questions", contains("What about fruit?", "And milk?")));

        verify(followUps, times(1)).suggest(anyString(), anyString(), anyCollection());
        assertThat(jdbc.sql("SELECT suggestions FROM chat_messages WHERE id = :id").param("id", firstAnswer)
                .query(String.class).single()).isEqualTo("[\"What about fruit?\",\"And milk?\"]");
    }

    @Test
    void anEmptyResultIsStoredToo() throws Exception {
        when(followUps.suggest(anyString(), anyString(), anyCollection())).thenReturn(List.of());

        call(conversationId, firstAnswer).andExpect(jsonPath("$.questions", empty()));
        call(conversationId, firstAnswer).andExpect(jsonPath("$.questions", empty()));

        verify(followUps, times(1)).suggest(anyString(), anyString(), anyCollection());
    }

    @Test
    void onlyTheFirstBotMessageOfTheConversationCounts() throws Exception {
        call(conversationId, secondAnswer).andExpect(status().isNotFound());
        call(UUID.randomUUID(), firstAnswer).andExpect(status().isNotFound());
        long userMessage = jdbc.sql("SELECT min(id) FROM chat_messages WHERE conversation_id = :c AND role = 'user'")
                .param("c", conversationId).query(Long.class).single();
        call(conversationId, userMessage).andExpect(status().isNotFound());

        verify(followUps, never()).suggest(any(), any(), any());
    }

    @Test
    void withoutCitedChunksThereAreNoQuestionsAndNoCall() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id) VALUES (:id)").param("id", other).update();
        long noSources = exchange(other, "Hi?", "Hello.", null);

        call(other, noSources).andExpect(status().isOk()).andExpect(jsonPath("$.questions", empty()));

        verify(followUps, never()).suggest(any(), any(), any());
    }

    @Test
    void theQuestionFromAButtonIsMarkedInTheDatabase() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id) VALUES (:id)").param("id", id).update();

        conversations.saveExchange(id, "What about fruit?", "Fruit is good.", new AnswerMetrics(1, 1, 1, 1), Map.of(), true);
        conversations.saveExchange(id, "And milk?", "Milk too.", new AnswerMetrics(1, 1, 1, 1), Map.of());

        assertThat(jdbc.sql("SELECT from_suggestion FROM chat_messages WHERE conversation_id = :c AND role = 'user' ORDER BY id")
                .param("c", id).query(Boolean.class).list()).containsExactly(true, false);
    }

    private ResultActions call(UUID conversation, long message) throws Exception {
        return mvc.perform(post("/api/followups").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\": \"" + conversation + "\", \"messageId\": " + message + "}"));
    }

    /** Una pregunta y su respuesta del bot (con lo que cita); devuelve el id de la respuesta. */
    private long exchange(UUID conversation, String question, String answer, String cited) {
        jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content) VALUES (:c, 'user', :q)")
                .param("c", conversation).param("q", question).update();
        return jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content, cited_chunks) VALUES (:c, 'assistant', :a, :cited) RETURNING id")
                .param("c", conversation).param("a", answer).param("cited", cited).query(Long.class).single();
    }
}
