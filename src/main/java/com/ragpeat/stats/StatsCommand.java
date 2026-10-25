package com.ragpeat.stats;

import java.util.List;
import java.util.Map;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Métricas de uso del chat, leídas de la base de datos.
 *   java -jar target\rag-peat-0.0.1-SNAPSHOT.jar stats
 *
 * Es un comando y no una página web a propósito: una página de administración habría que protegerla
 * con usuario y contraseña. Este comando solo lo puede lanzar quien tiene acceso al servidor.
 */
@Component
public class StatsCommand implements CommandLineRunner {

    // Precios de Claude Haiku 5.5 en dólares por token (0,10 y 0,50 $ por millón). Voyage es despreciable.
    private static final double INPUT_PRICE = 0.10 / 1_000_000, OUTPUT_PRICE = 0.50 / 1_000_000;

    private final JdbcClient jdbc;

    public StatsCommand(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0 || !args[0].equals("stats")) {
            return;
        }
        printVisits();
        printReferrers();
        printDaily();
        printTotals();
        printThumbsDown();
    }

    /** Cuánta gente abre la web cada día, pregunte o no; y qué parte de ella llega a preguntar. */
    private void printVisits() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT v.day AS dia,
                       count(*) AS visitantes,
                       (SELECT count(DISTINCT c.visitor_hash) FROM conversations c
                         WHERE c.created_at::date = v.day AND NOT c.internal) AS preguntan
                FROM page_views v
                GROUP BY v.day
                ORDER BY v.day DESC
                LIMIT 14
                """)
                .query()
                .listOfRows();

        System.out.println("\n=== Visitas a la web (personas distintas por día, fecha en UTC)");
        System.out.printf("%-12s %10s %16s%n", "día", "visitantes", "de ellos preguntan");
        for (Map<String, Object> r : rows) {
            System.out.printf("%-12s %10s %16s%n", r.get("dia"), r.get("visitantes"), r.get("preguntan"));
        }
        if (rows.isEmpty()) {
            System.out.println("(todavía ninguna)");
        }
    }

    /** De qué webs llega la gente (últimos 30 días). "directo": sin web de origen (enlace pegado, apps, WhatsApp...). */
    private void printReferrers() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT coalesce(referrer, 'directo') AS origen, count(*) AS visitantes
                FROM page_views
                WHERE day > CURRENT_DATE - 30
                GROUP BY 1
                ORDER BY 2 DESC
                LIMIT 15
                """)
                .query()
                .listOfRows();

        System.out.println("\n=== De dónde llegan (últimos 30 días)");
        for (Map<String, Object> r : rows) {
            System.out.printf("%-30s %6s%n", r.get("origen"), r.get("visitantes"));
        }
        if (rows.isEmpty()) {
            System.out.println("(todavía nada)");
        }
    }

    /** Una fila por día (últimos 14 días con actividad). Sin nuestras conversaciones (internal). */
    private void printDaily() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT c.created_at::date                       AS dia,
                       count(DISTINCT c.visitor_hash)           AS visitantes,
                       count(DISTINCT c.id)                     AS conversaciones,
                       count(*) FILTER (WHERE m.role = 'user')  AS preguntas,
                       round(avg(m.latency_ms) / 1000.0, 1)     AS segundos_medios,
                       coalesce(sum(m.input_tokens), 0)         AS tokens_entrada,
                       coalesce(sum(m.output_tokens), 0)        AS tokens_salida
                FROM conversations c
                JOIN chat_messages m ON m.conversation_id = c.id
                WHERE NOT c.internal
                GROUP BY dia
                ORDER BY dia DESC
                LIMIT 14
                """)
                .query()
                .listOfRows();

        System.out.println("\n=== Por día (fecha en UTC)");
        System.out.printf("%-12s %10s %15s %10s %16s %10s%n",
                "día", "visitantes", "conversaciones", "preguntas", "seg. respuesta", "coste $");
        for (Map<String, Object> r : rows) {
            double cost = ((Number) r.get("tokens_entrada")).longValue() * INPUT_PRICE
                    + ((Number) r.get("tokens_salida")).longValue() * OUTPUT_PRICE;
            System.out.printf("%-12s %10s %15s %10s %16s %10.4f%n", r.get("dia"), r.get("visitantes"),
                    r.get("conversaciones"), r.get("preguntas"), r.get("segundos_medios"), cost);
        }
    }

    private void printTotals() {
        Map<String, Object> t = jdbc.sql("""
                WITH visible AS (
                    SELECT m.* FROM chat_messages m
                    JOIN conversations c ON c.id = m.conversation_id
                    WHERE NOT c.internal
                ),
                per_conversation AS (
                    SELECT conversation_id,
                           count(*) FILTER (WHERE role = 'user')            AS questions,
                           extract(epoch FROM max(created_at) - min(created_at)) AS seconds
                    FROM visible
                    GROUP BY conversation_id
                )
                SELECT (SELECT count(*) FROM per_conversation)                              AS conversaciones,
                       (SELECT sum(questions) FROM per_conversation)                        AS preguntas,
                       (SELECT round(avg(questions), 1) FROM per_conversation)              AS preguntas_por_conversacion,
                       -- percentile_cont(0.5) = la mediana: el valor del medio, que no se deja
                       -- arrastrar por una conversación larguísima como haría la media.
                       (SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds)::numeric / 60, 1)
                          FROM per_conversation WHERE questions > 1)                        AS minutos_mediana,
                       (SELECT count(*) FROM visible WHERE feedback = 1)              AS votos_positivos,
                       (SELECT count(*) FROM visible WHERE feedback = -1)             AS votos_negativos,
                       (SELECT count(*) FROM visible WHERE role = 'assistant')        AS respuestas
                """)
                .query()
                .singleRow();

        long up = ((Number) t.get("votos_positivos")).longValue();
        long down = ((Number) t.get("votos_negativos")).longValue();
        long answers = ((Number) t.get("respuestas")).longValue();

        System.out.println("\n=== Totales");
        System.out.println("Conversaciones: " + t.get("conversaciones") + " · preguntas: " + t.get("preguntas")
                + " · preguntas por conversación: " + t.get("preguntas_por_conversacion"));
        System.out.println("Duración mediana de las conversaciones con más de una pregunta: "
                + (t.get("minutos_mediana") == null ? "-" : t.get("minutos_mediana") + " min"));
        System.out.printf("Votos: %d 👍 · %d 👎 (han votado %.0f%% de las respuestas)%n",
                up, down, answers == 0 ? 0.0 : 100.0 * (up + down) / answers);
    }

    /** Las últimas preguntas con 👎: candidatas a revisar y a añadir al banco de pruebas. */
    private void printThumbsDown() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT m.created_at::date AS dia,
                       (SELECT u.content FROM chat_messages u
                         WHERE u.conversation_id = m.conversation_id AND u.role = 'user' AND u.id < m.id
                         ORDER BY u.id DESC LIMIT 1) AS pregunta
                FROM chat_messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE m.feedback = -1 AND NOT c.internal
                ORDER BY m.id DESC
                LIMIT 10
                """)
                .query()
                .listOfRows();

        System.out.println("\n=== Últimas preguntas con 👎");
        if (rows.isEmpty()) {
            System.out.println("(ninguna)");
        }
        rows.forEach(r -> System.out.println(r.get("dia") + "  " + r.get("pregunta")));
        System.out.println();
    }
}
