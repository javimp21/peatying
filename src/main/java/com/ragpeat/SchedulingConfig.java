package com.ragpeat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activa las tareas programadas (@Scheduled, p. ej. RetentionJob) solo cuando arranca la web.
 * En los comandos de terminal (stats, index...) no: sus hilos impedirían que el programa terminara.
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication
public class SchedulingConfig {
}
