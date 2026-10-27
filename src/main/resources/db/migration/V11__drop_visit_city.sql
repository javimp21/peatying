-- La ciudad sacada de la IP no era fiable (con datos móviles sale la de la operadora): se deja solo el país.
ALTER TABLE page_views DROP COLUMN city;
