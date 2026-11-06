-- Una conversación puede tener varios documentos (hasta 3): sus nombres y textos se acumulan en
-- document_name y document_text, y aquí se cuenta cuántos lleva para no pasar del tope.
ALTER TABLE conversations ADD COLUMN document_count INT NOT NULL DEFAULT 0;
UPDATE conversations SET document_count = 1 WHERE document_text IS NOT NULL;
