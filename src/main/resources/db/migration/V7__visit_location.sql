-- País y ciudad aproximados del visitante, sacados de su IP con la base de datos DB-IP Lite.
-- La IP no se guarda: se consulta en memoria y se descarta. NULL = desconocido.
ALTER TABLE page_views ADD COLUMN country TEXT;
ALTER TABLE page_views ADD COLUMN city TEXT;
