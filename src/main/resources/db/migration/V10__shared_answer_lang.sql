-- Idioma de la página desde la que se compartió la respuesta ("es" o "en"): la página pública
-- (peatying.com/r/<id>) pone sus títulos y avisos en ese idioma.
ALTER TABLE shared_answers ADD COLUMN lang TEXT NOT NULL DEFAULT 'es';
