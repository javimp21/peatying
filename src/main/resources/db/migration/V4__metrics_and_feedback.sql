-- Visitantes únicos sin datos personales: una huella de (IP + navegador + un secreto que cambia cada día).
-- Sirve para contar visitantes de un día, pero no para saber quién es nadie ni seguirle entre días.
ALTER TABLE conversations ADD COLUMN visitor_hash TEXT;

-- Datos de cada respuesta del bot, para las métricas (en las preguntas del usuario quedan vacíos).
ALTER TABLE chat_messages
    ADD COLUMN latency_ms    INT,       -- cuánto tardó la respuesta completa
    ADD COLUMN input_tokens  INT,       -- para calcular el coste
    ADD COLUMN output_tokens INT,
    ADD COLUMN searches      INT,
    -- El voto del usuario: 1 = 👍, -1 = 👎, NULL = no ha votado.
    ADD COLUMN feedback      SMALLINT CHECK (feedback IN (-1, 1));
