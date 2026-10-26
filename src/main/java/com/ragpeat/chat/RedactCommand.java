package com.ragpeat.chat;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Pasa el filtro de datos personales (PersonalData) por los mensajes que YA están guardados.
 * Los nuevos se filtran al guardarlos; esto es para los anteriores, o si el filtro mejora.
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar redact
 */
@Component
public class RedactCommand implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RedactCommand.class);

    private final JdbcClient jdbc;

    public RedactCommand(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Message(long id, String content) {}

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("redact")) {
            return;
        }
        List<Message> messages = jdbc.sql("SELECT id, content FROM chat_messages").query(Message.class).list();
        int changed = 0;
        for (Message message : messages) {
            String redacted = PersonalData.redact(message.content());
            if (!redacted.equals(message.content())) {
                jdbc.sql("UPDATE chat_messages SET content = :content WHERE id = :id")
                        .param("content", redacted)
                        .param("id", message.id())
                        .update();
                changed++;
            }
        }
        log.info("Revisados {} mensajes: {} tenían datos personales y se han tachado", messages.size(), changed);

        // También el texto de los documentos adjuntos (analíticas...), y los motivos de los 👎.
        record Document(UUID id, String text) {}
        List<Document> documents = jdbc.sql("SELECT id, document_text AS text FROM conversations WHERE document_text IS NOT NULL")
                .query(Document.class).list();
        int changedDocs = 0;
        for (Document document : documents) {
            String redacted = PersonalData.redact(document.text());
            if (!redacted.equals(document.text())) {
                jdbc.sql("UPDATE conversations SET document_text = :text WHERE id = :id")
                        .param("text", redacted).param("id", document.id()).update();
                changedDocs++;
            }
        }
        int reasons = 0;
        for (Message reason : jdbc.sql("SELECT id, feedback_reason AS content FROM chat_messages WHERE feedback_reason IS NOT NULL")
                .query(Message.class).list()) {
            String redacted = PersonalData.redact(reason.content());
            if (!redacted.equals(reason.content())) {
                jdbc.sql("UPDATE chat_messages SET feedback_reason = :r WHERE id = :id")
                        .param("r", redacted).param("id", reason.id()).update();
                reasons++;
            }
        }
        log.info("Revisados {} documentos: {} tenían datos personales y se han tachado; motivos de 👎 tachados: {}",
                documents.size(), changedDocs, reasons);
    }
}
