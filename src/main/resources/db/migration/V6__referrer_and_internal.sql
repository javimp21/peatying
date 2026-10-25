-- De qué web llega cada visitante (solo el dominio, p. ej. "google.com"; NULL = directo o desconocido).
-- Se guarda el de su primera visita del día: la fila (día, huella) solo se crea una vez.
ALTER TABLE page_views ADD COLUMN referrer TEXT;

-- Conversaciones nuestras (del autor, desde un navegador marcado con ?interno): las métricas las excluyen.
ALTER TABLE conversations ADD COLUMN internal BOOLEAN NOT NULL DEFAULT false;
