package com.ragpeat.chat;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Documentos que admite una conversación: cada uno se le envía a Claude en todas las preguntas siguientes. */
    public static final int MAX_DOCUMENTS = 3;

    /** El texto de los documentos adjuntos a la conversación, o null. */
    public String documentText(UUID id) {
        return jdbc.sql("SELECT document_text FROM conversations WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    /** Cuántos documentos lleva la conversación (0 si no existe). */
    public int documentCount(UUID id) {
        return jdbc.sql("SELECT document_count FROM conversations WHERE id = :id")
                .param("id", id).query(Integer.class).optional().orElse(0);
    }

    /**
     * Añade un documento a los que ya tenga la conversación: solo el nombre y el texto, nunca el archivo.
     * Todo en una sola sentencia: dos subidas a la vez no se pisan ni pasan del tope.
     *
     * @return false si la conversación ya tenía el máximo (y entonces no se guarda)
     */
    public boolean addDocument(UUID id, String name, String text) {
        return jdbc.sql("""
                UPDATE conversations
                SET document_name = COALESCE(document_name || ', ', '') || :name,
                    document_text = COALESCE(document_text || :separator, '') || :section,
                    document_count = document_count + 1
                WHERE id = :id AND document_count < :max
                """)
                .param("id", id).param("name", name)
                .param("separator", "\n\n")
                .param("section", "[" + name + "]\n" + text)
                .param("max", MAX_DOCUMENTS)
                .update() > 0;
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
        return saveExchange(id, question, answer, metrics, Map.of());
    }

    /** @param cited los fragmentos que cita la respuesta: número de la cita -> id del chunk (ver V14). */
    @Transactional
    public long saveExchange(UUID id, String question, String answer, AnswerMetrics metrics, Map<Integer, Long> cited) {
        jdbc.sql("INSERT INTO chat_messages (conversation_id, role, content) VALUES (:id, 'user', :content)")
                .param("id", id)
                .param("content", PersonalData.redact(question))
                .update();
        return jdbc.sql("""
                INSERT INTO chat_messages
                    (conversation_id, role, content, latency_ms, input_tokens, output_tokens, searches, cited_chunks)
                VALUES (:id, 'assistant', :content, :latency, :input, :output, :searches, :cited)
                RETURNING id
                """)
                .param("id", id)
                .param("content", PersonalData.redact(answer))
                .param("latency", metrics.latencyMs())
                .param("input", metrics.inputTokens())
                .param("output", metrics.outputTokens())
                .param("searches", metrics.searches())
                .param("cited", cited.isEmpty() ? null : encodeCited(cited))
                .query(Long.class)
                .single();
    }

    /**
     * Lo que citó la ÚLTIMA respuesta del bot en la conversación (número de la cita -> id del chunk); vacío si no hay
     * respuestas o no citó nada.
     */
    public SortedMap<Integer, Long> lastAnswerCited(UUID id) {
        return decodeCited(jdbc.sql("""
                SELECT COALESCE(cited_chunks, '') FROM chat_messages
                WHERE conversation_id = :id AND role = 'assistant'
                ORDER BY id DESC
                LIMIT 1
                """)
                .param("id", id)
                .query(String.class)
                .optional().orElse(""));
    }

    /** "1:15342,3:8811" */
    static String encodeCited(Map<Integer, Long> cited) {
        return new TreeMap<>(cited).entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));
    }

    static SortedMap<Integer, Long> decodeCited(String text) {
        SortedMap<Integer, Long> cited = new TreeMap<>();
        if (text == null || text.isBlank()) {
            return cited;
        }
        for (String pair : text.split(",")) {
            String[] parts = pair.split(":");
            if (parts.length == 2) {
                try {
                    cited.put(Integer.parseInt(parts[0].strip()), Long.parseLong(parts[1].strip()));
                } catch (NumberFormatException e) {
                    // un par roto se ignora: esa cita simplemente no se enseña
                }
            }
        }
        return cited;
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
