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

### Fragmentos por búsqueda (27-10-2026), prompt v4

| 39 preguntas, Haiku | Nota | Responde (1-5) | Partes que sobran | Sin respaldo | Coste respuestas |
|---|---|---|---|---|---|
| 5 por búsqueda | 6,9 | 4,2 | 50 | 71 | 0,068 $ |
| 8 por búsqueda | 7,0 | 4,4 | 51 | 81 | 0,077 $ |
| 10 por búsqueda | 7,0 | 4,3 | 57 | 93 | 0,086 $ |

- Diagnóstico previo: en las respuestas flojas, lo que faltaba (zanahoria cruda para la digestión, vitamina A con
  el LDL, cómo evitar el exceso de hierro) estaba en el corpus pero no entre los fragmentos recibidos.
- Con más fragmentos llega algo más de material relevante, pero lo clave sigue sin llegar (la zanahoria: 1-2 de
  16-20) porque el modelo no sabe buscarlo, y sube lo atribuido sin respaldo. La nota no cambia más allá del ruido.
- Se mantienen 5 (`chat.results-per-search`, configurable).

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

### Juez ajustado al criterio del dueño y prompt v5 (27-10-2026)

Javi puntuó 10 respuestas del examen. El juez anterior era unos 2 puntos más duro con los matices de atribución y no
penalizaba lo que más le molesta: sacar contexto que nadie ha dado (la menstruación), comparaciones no pedidas,
responder a ciegas a una sola palabra y decir que Peat «no habló de X como tal». Se añadió ese criterio al juez.
Con el juez nuevo, sus notas y las del juez quedan a 1 punto o menos en 6 de 10 preguntas.

v5: pregunta qué quiere saber ante mensajes de una palabra, no supone nada de quien pregunta, no dice que Peat no
habló de algo (responde con el tema general), nunca dice que no dio cantidades, y guiones en vez de números.

| 39 preguntas, Haiku, juez ajustado | Nota | Responde (1-5) | Avisos innecesarios | Sin respaldo | Palabras |
|---|---|---|---|---|---|
| v4 | 7,3 | 4,2 | 17 | 80 | 190 |
| v5 | 7,5 | 4,2 | 11 | 81 | 179 |

### Banco automático con libros (27-10-2026)

Con los 5 libros en el corpus, el bot cita a menudo una página de libro en vez del artículo esperado, y el banco
lo contaba como fallo (28/34). Ahora cada pregunta lleva `bookTerms` (términos del tema en inglés): una página de
libro citada cuenta como fuente esperada si los menciona al menos dos veces. El texto de los libros también entra en
la comprobación de citas literales. Resultado: 32/34; los dos fallos son «setas» (solo cita entrevistas) y un
«no dejó una opinión» que el corrector no reconocía como admitir que Peat no habló de algo (ya corregido).

### Bot contra Claude Sonnet 5.5 con búsqueda web (28-10-2026)

12 conversaciones de 4 mensajes (`eval/compare-conversations.json`), sacadas de patrones reales del panel: palabras
clave sueltas, contexto personal que se arrastra, peticiones prácticas, temas de los que Peat habló poco y una
conversación en inglés. El rival es Claude Sonnet con búsqueda web y sin acceso a peatying.com, como alguien que
pregunta en claude.ai. Cuatro jueces (Claude Opus) ven las dos respuestas como A y B en orden aleatorio
(`eval/compare_packets.py`) y comprueban en los textos originales las 3 afirmaciones más concretas que cada respuesta
atribuye a Peat (`eval/judge-compare.md`).

| 48 mensajes | Bot (Haiku + RAG) | Sonnet + búsqueda web |
|---|---|---|
| Afirmaciones sobre Peat apoyadas en sus textos | 141/144 (98 %) | 78/93 (84 %) |
| No encontradas / contradichas | 2 / 1 | 13 / 2 |
| Nota media (1-10) | 8,0 | 5,8 |
| Responde a lo que se pide (1-5) | 4,6 | 3,9 |
| Mejor respuesta | 39 | 6 (3 empates) |

Comprobación manual de 15 veredictos del juez: 13 correctos; los 2 errores eran afirmaciones del bot marcadas como no
encontradas que sí están (creatina y vitamina E en *Nutrition for Women*; sal antes de dormir en una entrevista).
Sesgos a tener en cuenta: el juez busca en el mismo corpus que usa el bot, así que lo que Sonnet saca de boletines
o emails de Peat cuenta como «no encontrado»; y el estilo puede delatar cuál es cuál.

Sonnet ganó en: la conversación en inglés (el bot respondía en español a los seguimientos: corregido en el prompt,
el idioma lo decide el último mensaje; 8/8 en inglés al repetirla), las preguntas que piden juzgar a Peat («¿tenía
evidencia?», «¿se equivocó en algo?»: el bot solo usa sus textos y esquiva el juicio) y el minoxidil (el bot dijo
que Peat no habló de él; un artículo dice que hay que evitarlo y la búsqueda no lo encontró).
