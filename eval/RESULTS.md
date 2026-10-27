# Resultados de evaluación

## Calidad de las respuestas (26-10-2026)

`quality`: 31 preguntas reales de la web (`quality.json`, algunas con conversación previa), cada una con una nota
de qué se espera. Un juez (Claude Sonnet 5.5) puntúa si responde a lo que se pide, lo que sobra, los avisos
innecesarios, el formato y lo que se atribuye a Peat sin estar en los fragmentos citados.

| 31 preguntas | Nota | Responde (1-5) | Formato bien | Partes que sobran | Avisos innecesarios | Sin respaldo | Palabras | Coste por respuesta |
|---|---|---|---|---|---|---|---|---|
| Prompt anterior, Haiku | 6,2 | 3,9 | 14 | 55 | 29 | 53 | 188 | ~0,002 $ |
| Prompt nuevo (v2), Haiku | 6,8 | 4,2 | 29 | 35 | 13 | 66 | 183 | ~0,002 $ |
| Prompt nuevo (v3), Haiku | 6,7 | 4,2 | 31 | 48 | 16 | 72 | 195 | ~0,002 $ |
| Prompt nuevo (v3), Sonnet | 7,0 | 4,4 | 25 | 66 | 24 | 64 | 242 | ~0,03 $ |

- El prompt nuevo empieza por qué quiere la persona: si pide una cena o una rutina, la construye con las ideas de
  Peat en vez de decir que él no habló de ese caso. Prosa para explicar y listas solo para pasos u opciones.
- Se adopta v3 con Haiku: igual que v2 dentro del ruido, y corrige casos concretos (varias cenas, nada de primera
  persona). Sonnet mejora poco y cuesta unas 17 veces más.
- "Sin respaldo" es estricto: muchas son matices ("prefiero" contado como "recomendaba"). Es lo siguiente a mejorar.
- Banco automático (`eval`) con el mismo día: prompt anterior 30/34, nuevo 31/34 (los fallos son de fuentes esperadas).
- Una ejecución por variante: diferencias de ±0,3 en la nota son ruido.

### v4 (27-10-2026), 39 preguntas (8 más, de conversaciones propias)

| 39 preguntas, Haiku | Nota | Responde (1-5) | Formato bien | Partes que sobran | Avisos innecesarios | Sin respaldo | Palabras |
|---|---|---|---|---|---|---|---|
| v3, pasada 1 | 6,8 | 4,2 | 39 | 57 | 23 | 83 | 187 |
| v3, pasada 2 | 6,6 | 4,1 | 36 | 55 | 15 | 87 | 181 |
| v4 | 6,9 | 4,2 | 37 | 50 | 18 | 71 | 191 |

- v4: en las propuestas, cada punto separa la propuesta (sin atribuírsela a Peat) de la razón (lo que él dijo, con
  cita), y elige 3 o 4 ideas antes de escribir. Baja lo atribuido sin respaldo (71 frente a 83-87); la nota sube
  poco, al límite del ruido. Se adopta.
- Pendiente: respuestas demasiado largas para preguntas de una palabra («patata»).

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
