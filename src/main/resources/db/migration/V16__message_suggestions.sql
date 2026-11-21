-- Preguntas de seguimiento que se ofrecen como botones bajo la primera respuesta de cada conversación (ver FollowUps).
-- suggestions: array JSON con las preguntas, en el mensaje del bot; NULL = aún no se han generado, [] = no había (no se vuelve a intentar).
ALTER TABLE chat_messages ADD COLUMN suggestions TEXT;
-- from_suggestion: en el mensaje del usuario, true si la pregunta salió de pulsar uno de esos botones (para medir si se usan).
ALTER TABLE chat_messages ADD COLUMN from_suggestion BOOLEAN NOT NULL DEFAULT false;
