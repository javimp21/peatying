-- Qué fragmentos citó cada respuesta del bot: "1:15342,3:8811" (número de la cita : id del chunk).
-- En la pregunta siguiente se le vuelven a enseñar al bot, para que pueda comprobar lo que dijo si la persona se lo
-- discute. Sin ellos se retractaba de cosas ciertas: la búsqueda nueva no traía el fragmento de antes.
-- Solo ids, sin clave ajena: al reindexar, un chunk puede desaparecer, y entonces esa cita simplemente no se enseña.
ALTER TABLE chat_messages ADD COLUMN cited_chunks TEXT;
