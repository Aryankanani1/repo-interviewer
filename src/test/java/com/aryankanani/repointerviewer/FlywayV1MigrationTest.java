package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 0.3 acceptance test: Flyway applied V1__init.sql to a real pgvector Postgres.
 * Uses the shared {@link IntegrationTest} context and container.
 */
@IntegrationTest
class FlywayV1MigrationTest {

	private static final List<String> V1_TABLES = List.of(
			"project", "code_file", "code_chunk", "interview_session",
			"interview_topic", "question", "answer", "llm_call");

	private final JdbcTemplate jdbc;

	FlywayV1MigrationTest(@Autowired JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Test
	void vectorExtensionIsInstalled() {
		List<String> extensions = jdbc.queryForList(
				"SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);

		assertThat(extensions).containsExactly("vector");
	}

	@Test
	void allV1TablesExist() {
		List<String> tables = jdbc.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
				""", String.class);

		// containsAll, not containsExactly: later migrations (V2 outbox, ...) add more tables.
		assertThat(tables).containsAll(V1_TABLES);
	}

	@Test
	void flywayRecordsVersion1AsSuccessful() {
		Boolean success = jdbc.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

		assertThat(success).isTrue();
	}

	@Test
	void embeddingColumnMatchesEmbeddingModelDimension() {
		String type = jdbc.queryForObject("""
				SELECT format_type(atttypid, atttypmod) FROM pg_attribute
				WHERE attrelid = 'code_chunk'::regclass AND attname = 'embedding'
				""", String.class);

		// nomic-embed-text returns 768 numbers (DECISIONS.md D-005). Any other size fails every insert.
		assertThat(type).isEqualTo("vector(768)");
	}

}
