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

    /**
     * Crea una conversación nueva. El id es un UUID aleatorio: nadie puede adivinar el de otra persona.
     * visitorHash: huella anónima del visitante (ver VisitorHasher), solo para contar visitantes únicos.
     */
    /** @param internal true si la conversación es nuestra (navegador marcado con ?interno): no cuenta en las métricas. */
    public UUID create(String visitorHash, boolean internal) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id, visitor_hash, internal) VALUES (:id, :visitor, :internal)")
                .param("id", id)
                .param("visitor", visitorHash)
                .param("internal", internal)
                .update();
        return id;
    }

    /** El texto del documento adjunto a la conversación, o null. */
    public String documentText(UUID id) {
        return jdbc.sql("SELECT document_text FROM conversations WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    /** Guarda (o sustituye) el documento de la conversación: solo el nombre y el texto, nunca el archivo. */
    public void saveDocument(UUID id, String name, String text) {
        jdbc.sql("UPDATE conversations SET document_name = :name, document_text = :text WHERE id = :id")
                .param("id", id).param("name", name).param("text", text).update();
    }

    /** Datos de una respuesta del bot para las métricas. */
    public record AnswerMetrics(long latencyMs, long inputTokens, long outputTokens, int searches) {}

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

    /**
     * Guarda una pregunta y su respuesta juntas: o las dos, o ninguna.
     * Antes tacha los datos personales (ver PersonalData): p. ej. el DNI de una analítica pegada.
     * @return el id del mensaje de la respuesta (la página lo necesita para votar 👍/👎).
     */
    @Transactional
    public long saveExchange(UUID id, String question, String answer, AnswerMetrics metrics) {
        jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content) VALUES (:id, 'user', :content)")
                .param("id", id)
                .param("content", PersonalData.redact(question))
                .update();
        return jdbc.sql("""
                INSERT INTO chat_messages
                    (conversation_id, role, content, latency_ms, input_tokens, output_tokens, searches)
                VALUES (:id, 'assistant', :content, :latency, :input, :output, :searches)
                RETURNING id
                """)
                .param("id", id)
                .param("content", PersonalData.redact(answer))
                .param("latency", metrics.latencyMs())
                .param("input", metrics.inputTokens())
                .param("output", metrics.outputTokens())
                .param("searches", metrics.searches())
                .query(Long.class)
                .single();
    }

    /**
     * Guarda el voto (1, -1, o null para quitarlo) y, con 👎, el motivo opcional.
     * Exigimos que el mensaje sea de ESA conversación: el id del mensaje es un número fácil de
     * adivinar, pero el de la conversación es un UUID secreto que solo tiene quien la creó.
     * @return false si no existe ese mensaje del bot en esa conversación.
     */
    public boolean saveFeedback(UUID conversationId, long messageId, Integer value, String reason) {
        // El motivo lo escribe la persona: pasa por el mismo filtro de datos personales que las preguntas.
        String cleanReason = reason == null || reason.isBlank() ? null : PersonalData.redact(reason.strip());
        return jdbc.sql("""
                UPDATE chat_messages SET feedback = :value, feedback_reason = :reason
                WHERE id = :message AND conversation_id = :conversation AND role = 'assistant'
                """)
                .param("value", value)
                .param("reason", cleanReason)
                .param("message", messageId)
                .param("conversation", conversationId)
                .update() == 1;
    }
}
