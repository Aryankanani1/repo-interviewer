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
 * Task 4.1: the services share one Postgres, but not their data. Postgres itself refuses analysis_svc everything
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
		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("analysis_svc");
		assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("analysis");
		assertThat(jdbc.queryForObject("""
				SELECT schemaname FROM pg_tables WHERE tablename = 'project' AND tableowner = 'analysis_svc'
				""", String.class)).isEqualTo("analysis");
	}

	@Test
	void anotherServicesTableCannotBeRead() throws SQLException {
		try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword()); Statement sql = admin.createStatement()) {
			// Like the app's tables: in public, owned by another user.
			sql.execute("CREATE TABLE IF NOT EXISTS public.interview_session_probe (id INT)");
			sql.execute("INSERT INTO public.interview_session_probe VALUES (1)");
		}

		assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM public.interview_session_probe", Long.class))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied for table interview_session_probe");
	}

	@Test
	void noTableCanBeCreatedOutsideItsSchema() {
		assertThatThrownBy(() -> jdbc.execute("CREATE TABLE public.sneaky (id INT)"))
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied for schema public");
	}

}
