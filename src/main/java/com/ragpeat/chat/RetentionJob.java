package com.ragpeat.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * No guardamos nada para siempre (ver la página de privacidad):
 *  - conversaciones (y sus mensajes, por el ON DELETE CASCADE): 12 meses;
 *  - visitas (huella anónima del día, origen y país): 24 meses, para ver tendencias.
 * Se ejecuta cada noche; si el servidor estuvo apagado, lo pendiente se borra en la siguiente pasada.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;

    public RetentionJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Europe/Madrid")
    public void purge() {
        int conversations = jdbc.sql("DELETE FROM conversations WHERE created_at < now() - interval '12 months'").update();
        int visits = jdbc.sql("DELETE FROM page_views WHERE day < CURRENT_DATE - interval '24 months'").update();
        if (conversations > 0 || visits > 0) {
            log.info("Borradas {} conversaciones de hace más de 12 meses y {} visitas de hace más de 24", conversations, visits);
        }
    }
}
