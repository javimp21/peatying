package com.ragpeat.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.ragpeat.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Paginar las conversaciones del panel con "before", contra la base de datos real. */
@SpringBootTest(properties = "admin.token=" + AdminConversationsPagingTest.TOKEN)
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminConversationsPagingTest {

    static final String TOKEN = "clave-de-prueba-de-mas-de-veinte-letras";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    MockMvc mvc;
    Instant t1, t2, t3;
    UUID c1, c2, c3;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        t1 = now.minusSeconds(300);
        t2 = now.minusSeconds(200);
        t3 = now.minusSeconds(100);
        c1 = conversationAt(t1);
        c2 = conversationAt(t2);
        c3 = conversationAt(t3);
    }

    @Test
    void withoutBeforeItReturnsEverythingNewestFirst() throws Exception {
        conversations("").andExpect(jsonPath("$[*].id", contains(c3.toString(), c2.toString(), c1.toString())));
    }

    @Test
    void beforeKeepsOnlyOlderConversations() throws Exception {
        conversations("&before=" + t3).andExpect(jsonPath("$[*].id", contains(c2.toString(), c1.toString())));
    }

    @Test
    void beforeAndLimitGiveTheNextPage() throws Exception {
        conversations("&before=" + t3 + "&limit=1").andExpect(jsonPath("$[*].id", contains(c2.toString())));
    }

    @Test
    void nothingIsOlderThanTheFirstConversation() throws Exception {
        conversations("&before=" + t1).andExpect(jsonPath("$", empty()));
    }

    private ResultActions conversations(String extraParams) throws Exception {
        return mvc.perform(get("/api/admin/conversations?internas=false" + extraParams)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    private UUID conversationAt(Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id, created_at) VALUES (:id, :at)")
                .param("id", id).param("at", Timestamp.from(at)).update();
        jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content, created_at) VALUES (:id, 'user', 'q', :at)")
                .param("id", id).param("at", Timestamp.from(at)).update();
        return id;
    }
}
