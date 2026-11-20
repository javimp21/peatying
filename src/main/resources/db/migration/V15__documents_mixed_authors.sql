-- Páginas escaneadas de revistas con varios autores (Townsend Letter for Doctors): en la misma página están las
-- columnas y cartas de Peat y las cartas de otros. El OCR mezcla las columnas, así que un chunk puede traer texto de
-- otra persona. Con esta marca, el bot sabe que no todo lo que lee ahí es de Peat.
ALTER TABLE documents ADD COLUMN mixed_authors BOOLEAN NOT NULL DEFAULT false;

-- Se marcan TODAS las páginas de un número si alguna menciona la revista. Se detecta por el contenido, sin
-- listar números a mano. (Al reindexar, ChunkRepository.upsertDocument no toca esta columna: la marca se conserva.)
UPDATE documents SET mixed_authors = true
WHERE source_type = 'newsletter'
  AND regexp_replace(slug, '-p[0-9]+$', '') IN (
      SELECT DISTINCT regexp_replace(d.slug, '-p[0-9]+$', '')
      FROM documents d JOIN chunks c ON c.document_id = d.id
      WHERE d.source_type = 'newsletter' AND c.content ~* 'TOWNSEN[DO] LETTER');
