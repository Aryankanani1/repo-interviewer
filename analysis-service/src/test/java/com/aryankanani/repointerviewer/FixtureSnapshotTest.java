package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectContext;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The snapshot that the other services' tests use instead of this service ({@link FixtureAnalysis}, read by
 * {@code FakeAnalysisApi}) is what this service really stores for the fixture repository (task 4.1). When the
 * indexer, the chunker or the fixture changes, this fails; then write the snapshot again and commit it:
 * {@code ./mvnw -pl analysis-service -am verify -Dtest=FixtureSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false
 * -Dfixture.snapshot.update=true}
 */
@IntegrationTest
class FixtureSnapshotTest {

	/** From this module's folder, where Maven runs the tests. */
	private static final Path SNAPSHOT = Path.of("..", "libs", "test-support", "src", "main", "resources",
			FixtureAnalysis.RESOURCE);

	private final FixtureIndexer fixtures;

	private final AnalysisApi analysis;

	private final JdbcTemplate jdbc;

	FixtureSnapshotTest(@Autowired FixtureIndexer fixtures, @Autowired AnalysisApi analysis,
			@Autowired JdbcTemplate jdbc) {
		this.fixtures = fixtures;
		this.analysis = analysis;
		this.jdbc = jdbc;
	}

	@Test
	void theSnapshotOfTheFixturesAnalysisIsUpToDate() throws IOException {
		String actual = analysisOf(fixtures.readyProject()).toJson();
		if (Boolean.getBoolean("fixture.snapshot.update")) {
			Files.createDirectories(SNAPSHOT.getParent());
			Files.writeString(SNAPSHOT, actual);
		}
		assertThat(Files.exists(SNAPSHOT)).as("%s is missing: run this test with -Dfixture.snapshot.update=true",
				SNAPSHOT).isTrue();
		assertThat(Files.readString(SNAPSHOT))
				.as("%s is out of date: run this test with -Dfixture.snapshot.update=true and commit it", SNAPSHOT)
				.isEqualTo(actual);
	}

	private FixtureAnalysis analysisOf(UUID project) {
		ProjectDto dto = analysis.findByIds(List.of(project)).getFirst();
		ProjectContext context = analysis.context(project);
		List<FixtureAnalysis.File> files = jdbc.query("""
				SELECT path, role, content FROM code_file WHERE project_id = ? ORDER BY path COLLATE "C"
				""", (rs, row) -> new FixtureAnalysis.File(rs.getString("path"), rs.getString("role"),
				rs.getString("content")), project);
		List<FixtureAnalysis.Chunk> chunks = jdbc.query("""
				SELECT path, symbol, chunk_type, start_line, end_line, content, token_estimate
				FROM code_chunk WHERE project_id = ? ORDER BY path COLLATE "C", start_line, end_line, chunk_type
				""", (rs, row) -> new FixtureAnalysis.Chunk(rs.getString("path"), rs.getString("symbol"),
				rs.getString("chunk_type"), rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("content"),
				rs.getInt("token_estimate")), project);
		return new FixtureAnalysis(context.repoMap(), context.summary(), dto.stats(), files, chunks);
	}

}
