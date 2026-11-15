package com.aryankanani.repointerviewer.codebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.9: the PARSING stage as one call, and the names other modules may point at. */
@IntegrationTest
class CodebaseIndexerTest {

	private final CodebaseIndexer indexer;

	private final CodebaseQueries queries;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	CodebaseIndexerTest(@Autowired CodebaseIndexer indexer, @Autowired CodebaseQueries queries,
			@Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc) {
		this.indexer = indexer;
		this.queries = queries;
		this.fixture = fixture;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void deleteProjects() {
		jdbc.update("DELETE FROM project");
	}

	@Test
	void indexesTheFixtureAndReportsWhatItSaved() {
		UUID project = fixture.newProject();

		IndexResult result = indexer.index(project, LocalDirectoryRepoFetcher.fixtureRoot());

		assertThat(result.fileCount()).isEqualTo(11).isEqualTo(count("code_file", project));
		assertThat(result.javaFileCount()).isEqualTo(7);
		assertThat(result.chunkCount()).isEqualTo(29).isEqualTo(count("code_chunk", project));
		assertThat(result.primaryLanguage()).isEqualTo("java");
		assertThat(result.repoMap()).contains("OrderController [CONTROLLER]");
		assertThat(result.buildFiles()).startsWith("pom.xml");
		assertThat(result.readme()).startsWith("# Shop");
		assertThat(result.toString()).doesNotContain("Shop", "pom.xml");   // repository text stays out of logs
	}

	@Test
	void indexingAgainReplacesTheEarlierRows() {
		UUID project = fixture.newProject();

		indexer.index(project, LocalDirectoryRepoFetcher.fixtureRoot());
		indexer.index(project, LocalDirectoryRepoFetcher.fixtureRoot());

		assertThat(count("code_file", project)).isEqualTo(11);
		assertThat(count("code_chunk", project)).isEqualTo(29);
	}

	@Test
	void knownNamesAreTypesNestedTypesAndFileNames() {
		UUID project = fixture.newProject();
		indexer.index(project, LocalDirectoryRepoFetcher.fixtureRoot());

		assertThat(queries.knownNames(project)).contains("OrderService", "Order", "Order.Status", "Status",
				"RefundRequest", "OrderServiceTest", "OrderService.java", "application.yml", "pom.xml",
				"V1__orders.sql", "README.md").doesNotContain("JpaRepository", "application");
	}

	private int count(String table, UUID project) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?", Integer.class, project);
	}

}
