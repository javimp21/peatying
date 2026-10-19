# Resultados de evaluación

## RAG vs. Claude sin RAG (19-10-2026)

Mismo modelo para responder en los dos casos (Claude Haiku 5.5). El juez (Claude Sonnet 5.5) extrae de cada
respuesta las afirmaciones que atribuye a Peat y comprueba cada una contra los fragmentos de sus artículos más
relacionados con esa afirmación (búsqueda sobre los 78 artículos, no solo sobre lo que encontró el bot).

| 30 preguntas | Afirmaciones | Respaldadas | Sin respaldo | Contradichas |
|---|---|---|---|---|
| RAG | 202 | 198 (98 %) | 4 | 0 |
| Claude sin RAG | 198 | 144 (73 %) | 47 | 7 |

Coste de la ejecución: ~2,77 $ (casi todo el juez). Detalle: `results/20261019-2351-compare.json`.

**Ejemplo:** sin RAG, Claude atribuye a Peat que el ácido láctico es "un combustible útil" y "una forma
eficiente de producir energía". Peat escribió lo contrario: "I have begun thinking of lactic acid as a toxin".

**Cómo leerlo:**
- "Sin respaldo" = no comprobable en estos 78 artículos, no necesariamente falso (Peat pudo decirlo en
  entrevistas o newsletters).
- De las 7 contradichas, 6 son claras (lactato, vitamina E); 1 es dudosa (kombucha).
- Las 4 sin respaldo del RAG parecen en parte ruido del juez.
- Una sola ejecución, 30 preguntas escritas a mano, juez automático: es una señal, no una verdad absoluta.

## Banco de pruebas automático (sin juez)

`eval` con Claude Haiku 5.5: 29/30 aprobadas. El único fallo fue un falso positivo del corrector
(«medicina moderna» tomada por cita en inglés). 7 citas literales, ninguna inventada. Coste ~0,03 $.
