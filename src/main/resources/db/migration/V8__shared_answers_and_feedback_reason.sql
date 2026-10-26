-- Respuestas compartidas (peatying.com/r/<id>): una COPIA de la pregunta y la respuesta, hecha cuando
-- alguien pulsa "Compartir". Es una copia y no un enlace al mensaje para que siga existiendo aunque la
-- conversación se borre (a los 12 meses). id: aleatorio, imposible de adivinar.
CREATE TABLE shared_answers (
    id         TEXT PRIMARY KEY,
    message_id BIGINT UNIQUE,                         -- de qué respuesta salió (para no duplicarla si se comparte dos veces)
    question   TEXT NOT NULL,
    answer     TEXT NOT NULL,
    sources    JSONB NOT NULL,                        -- [{"number": 1, "title": "...", "url": "...", "type": "article"}]
    views      INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Con un 👎, la persona puede contar qué ha fallado (opcional).
ALTER TABLE chat_messages ADD COLUMN feedback_reason TEXT;
