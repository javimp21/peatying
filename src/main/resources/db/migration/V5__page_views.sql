-- Visitas a la página, aunque no pregunten nada. Una fila por visitante y día como mucho:
-- la clave primaria (día, huella) hace que la misma persona el mismo día solo cuente una vez.
-- La huella es la misma huella anónima diaria de VisitorHasher: ni IP, ni cookies.
CREATE TABLE page_views (
    day          DATE NOT NULL,
    visitor_hash TEXT NOT NULL,
    PRIMARY KEY (day, visitor_hash)
);
