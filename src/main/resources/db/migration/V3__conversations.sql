-- Conversaciones del chat: para que el bot recuerde lo anterior ("¿y por qué?") y, más adelante, métricas.
-- No guardamos nada que identifique a la persona: ni IP, ni cuenta, ni cookies. Solo el texto.
CREATE TABLE conversations (
    id         UUID PRIMARY KEY,                      -- aleatorio e imposible de adivinar (lo genera Java)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            TEXT NOT NULL CHECK (role IN ('user', 'assistant')),   -- CHECK: Postgres rechaza otro valor
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Siempre buscamos "los últimos mensajes de una conversación": este índice lo hace rápido.
CREATE INDEX chat_messages_conversation_idx ON chat_messages (conversation_id, id);
