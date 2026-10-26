-- Documento adjunto a una conversación (p. ej. una analítica en PDF o en foto). No se guarda el archivo:
-- solo su TEXTO, sacado por Claude y pasado por el filtro de datos personales, igual que si se hubiera pegado.
-- Va con la conversación para que el bot lo tenga delante en todas las preguntas, y se borra con ella.
ALTER TABLE conversations ADD COLUMN document_name TEXT;
ALTER TABLE conversations ADD COLUMN document_text TEXT;
