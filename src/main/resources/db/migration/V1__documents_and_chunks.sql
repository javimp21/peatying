-- pgvector: añade a Postgres el tipo "vector" para guardar embeddings (lo usaremos en V2).
CREATE EXTENSION IF NOT EXISTS vector;

-- Un documento = un artículo (más adelante también entrevistas, etc.).
CREATE TABLE documents (
    id          BIGSERIAL PRIMARY KEY,
    slug        TEXT NOT NULL UNIQUE,           -- "gelatin": identifica el artículo
    title       TEXT NOT NULL,
    url         TEXT NOT NULL,
    author      TEXT NOT NULL DEFAULT 'Ray Peat',
    source_type TEXT NOT NULL DEFAULT 'article'
);

-- Un fragmento (chunk) = un trozo de un documento. Es lo que buscaremos y daremos al modelo.
CREATE TABLE chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    position    INT    NOT NULL,                -- 0, 1, 2... orden dentro del artículo
    content     TEXT   NOT NULL,
    -- Versión del texto preparada para búsqueda por palabras (en inglés: "thyroids" -> "thyroid").
    -- GENERATED ... STORED: Postgres la calcula sola al insertar; nunca la escribimos nosotros.
    content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    UNIQUE (document_id, position)
);

-- Índice GIN: hace rápida la búsqueda por palabras sobre content_tsv.
CREATE INDEX chunks_content_tsv_idx ON chunks USING GIN (content_tsv);
