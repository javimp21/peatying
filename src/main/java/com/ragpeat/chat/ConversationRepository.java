package com.ragpeat.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Guarda y lee las conversaciones del chat. */
@Repository
public class ConversationRepository {

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Crea una conversación nueva. El id es un UUID aleatorio: nadie puede adivinar el de otra persona. */
    public UUID create() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id) VALUES (:id)").param("id", id).update();
        return id;
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM conversations WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Los últimos mensajes de la conversación, del más antiguo al más reciente.
     * La subconsulta coge los N más recientes (ORDER BY id DESC); la de fuera les da la vuelta.
     */
    public List<Turn> lastTurns(UUID id, int limit) {
        return jdbc.sql("""
                SELECT role, content FROM (
                    SELECT id, role, content FROM chat_messages
                    WHERE conversation_id = :id
                    ORDER BY id DESC
                    LIMIT :limit
                ) recent
                ORDER BY id
                """)
                .param("id", id)
                .param("limit", limit)
                .query(Turn.class)
                .list();
    }

    /** Guarda una pregunta y su respuesta juntas: o las dos, o ninguna. */
    @Transactional
    public void saveExchange(UUID id, String question, String answer) {
        insert(id, Turn.USER, question);
        insert(id, Turn.ASSISTANT, answer);
    }

    private void insert(UUID id, String role, String content) {
        jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content) VALUES (:id, :role, :content)")
                .param("id", id)
                .param("role", role)
                .param("content", content)
                .update();
    }
}
