package com.ragpeat;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Una base de datos PostgreSQL desechable para los tests de integración.
 *
 * Es la misma imagen que usa compose.yaml (Postgres con pgvector, que las migraciones necesitan). Con
 * @ServiceConnection, Spring apunta la conexión a este contenedor sin configurar nada, y Flyway aplica
 * todas las migraciones desde cero. Cada contexto de Spring distinto arranca su propio contenedor.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    }
}
