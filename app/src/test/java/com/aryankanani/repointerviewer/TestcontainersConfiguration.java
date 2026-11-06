package com.aryankanani.repointerviewer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres with the pgvector extension for tests. {@code @ServiceConnection} points the
 * datasource at the container, so no URL or password is configured anywhere.
 * Tests never start Ollama: they use {@code FakeEmbeddingModel} (DECISIONS.md D-012).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Same image as docker-compose.yml. It is a Postgres image with pgvector added, so it is
	// declared as a compatible substitute for the official "postgres" image.
	static final DockerImageName PGVECTOR_IMAGE =
			DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE);
	}

}
