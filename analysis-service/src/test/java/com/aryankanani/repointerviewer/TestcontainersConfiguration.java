package com.aryankanani.repointerviewer;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Postgres for analysis-service's tests, set up like a real database (task 4.1): the container runs
 * {@code db/bootstrap.sql} at its first start, as Compose does on a new volume, and the service connects as
 * {@code analysis_svc}, not as the container's superuser. So the tests run with the same rights as production:
 * a query outside the schema "analysis" fails here too ({@code SchemaIsolationTest}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Same image as docker-compose.yml: Postgres with pgvector, a compatible substitute for the official image.
	static final DockerImageName PGVECTOR_IMAGE =
			DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

	static final String SERVICE_USER = "analysis_svc";

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE).withCopyFileToContainer(
				MountableFile.forClasspathResource("db/bootstrap.sql"), "/docker-entrypoint-initdb.d/10-analysis.sql");
	}

	/** The datasource (and so Flyway) logs in as the service's own user; its password is its name locally. */
	@Bean
	JdbcConnectionDetails analysisConnection(PostgreSQLContainer postgres) {   // started: Boot starts container beans
		return new JdbcConnectionDetails() {

			@Override
			public String getJdbcUrl() {
				return postgres.getJdbcUrl();
			}

			@Override
			public String getUsername() {
				return SERVICE_USER;
			}

			@Override
			public String getPassword() {
				return SERVICE_USER;
			}

		};
	}

}
