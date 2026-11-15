package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 4.1: the services share one Postgres, but not their data. Postgres itself refuses interview_svc everything
 * outside its own schema, so no code, mistake or injected SQL in this service can read another service's tables.
 */
@IntegrationTest
class SchemaIsolationTest {

	private final JdbcTemplate jdbc;

	private final PostgreSQLContainer postgres;

	SchemaIsolationTest(@Autowired JdbcTemplate jdbc, @Autowired PostgreSQLContainer postgres) {
		this.jdbc = jdbc;
		this.postgres = postgres;
	}

	@Test
	void theServiceIsItsOwnUserInItsOwnSchema() {
		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("interview_svc");
		assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("interview");
		assertThat(jdbc.queryForObject("""
				SELECT schemaname FROM pg_tables WHERE tablename = 'interview_session' AND tableowner = 'interview_svc'
				""", String.class)).isEqualTo("interview");
	}

	@Test
	void anotherServicesTableCannotBeRead() throws SQLException {
		try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()); Statement sql = admin.createStatement()) {
			// Like another service's table (analysis-service's project): owned by another user, outside this schema.
			sql.execute("CREATE TABLE IF NOT EXISTS public.project_probe (id INT)");
			sql.execute("INSERT INTO public.project_probe VALUES (1)");
		}

		assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM public.project_probe", Long.class))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied for table project_probe");
	}

	@Test
	void noTableCanBeCreatedOutsideItsSchema() {
		assertThatThrownBy(() -> jdbc.execute("CREATE TABLE public.sneaky (id INT)"))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied for schema public");
	}

}
