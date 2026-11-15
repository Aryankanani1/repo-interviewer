package com.aryankanani.repointerviewer;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Postgres for interview-service's tests, set up like a real database (task 4.1): the container runs
 * {@code db/bootstrap.sql} at its first start, as Compose does on a new volume, and the service connects as
 * {@code interview_svc}, not as the container's superuser. So the tests run with the same rights as production:
 * a query outside the schema "interview" fails here too ({@code SchemaIsolationTest}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Same image as docker-compose.yml, so the services share one Postgres there and in production.
	static final DockerImageName PGVECTOR_IMAGE =
			DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

	public static final String SERVICE_USER = "interview_svc";

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE).withCopyFileToContainer(
				MountableFile.forClasspathResource("db/bootstrap.sql"), "/docker-entrypoint-initdb.d/10-interview.sql");
	}

	/** The datasource (and so Flyway) logs in as the service's own user; its password is its name locally. */
	@Bean
	JdbcConnectionDetails interviewConnection(PostgreSQLContainer postgres) {   // started: Boot starts container beans
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
