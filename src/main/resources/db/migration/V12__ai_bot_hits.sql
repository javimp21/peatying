-- Cuántas veces lee cada página cada rastreador de IA, por día (ver AiBotCounter). Sin IP ni nada de personas.
-- Las filas tienen un tope natural: solo se cuentan rastreadores de una lista y páginas que existen.
CREATE TABLE ai_bot_hits (
    day  DATE    NOT NULL,
    bot  TEXT    NOT NULL,
    page TEXT    NOT NULL,
    hits INTEGER NOT NULL,
    PRIMARY KEY (day, bot, page)
);
