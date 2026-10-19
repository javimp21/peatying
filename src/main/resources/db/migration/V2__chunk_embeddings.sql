-- El embedding de cada chunk: 1024 números (lo que devuelve el modelo voyage-4).
-- Empieza vacío (NULL) y lo rellena el comando "embed".
ALTER TABLE chunks ADD COLUMN embedding vector(1024);

-- Sin índice vectorial a propósito: con ~1.700 filas, Postgres compara la pregunta con
-- todas en milisegundos (búsqueda exacta). Un índice (HNSW) solo compensa con decenas de miles.
