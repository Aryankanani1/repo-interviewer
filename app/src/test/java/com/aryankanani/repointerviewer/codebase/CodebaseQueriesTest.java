package com.aryankanani.repointerviewer.codebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.contracts.analysis.SnippetRef;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link CodebaseQueries#snippets} cuts the cited lines from the stored, redacted file (V2), so lines that only a
 * TYPE_HEADER covers show too: RESULTS.md problem 3 of the first real run (task 1.15). Files analyzed before V2 keep
 * the chunk-based snippets.
 */
@IntegrationTest
class CodebaseQueriesTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/Order.java";

	private static final String CONFIG = "src/main/resources/application.yml";

	private final CodebaseQueries codebase;

	private final ChunkStore chunkStore;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	private UUID project;

	private List<String> order;

	CodebaseQueriesTest(@Autowired CodebaseQueries codebase, @Autowired ChunkStore chunkStore,
			@Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc) {
		this.codebase = codebase;
		this.chunkStore = chunkStore;
		this.fixture = fixture;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void indexFixture() throws Exception {
		jdbc.update("DELETE FROM project");   // cascades to code_file and code_chunk
		project = fixture.newProject();
		fixture.index(project);
		order = Files.readAllLines(LocalDirectoryRepoFetcher.fixtureRoot().resolve(ORDER));
	}

	/** The 1.15 case: a ref on class annotations and fields, which only the condensed TYPE_HEADER covers. */
	@Test
	void aRefOnHeaderOnlyLinesShowsTheExactSource() {
		List<String> snippets = codebase.snippets(List.of(new SnippetRef(project, ORDER, 14, 25)));

		assertThat(snippets.getFirst()).isEqualTo(lines(14, 25)).startsWith("@Entity\n@Table(name = \"orders\")");
		assertThat(chunkStore.snippets(List.of(new SnippetRef(project, ORDER, 20, 25))).getFirst())
				.as("the chunks alone have no exact copy of these lines").isNull();
	}

	@Test
	void aRefAcrossFieldsAndMethodsShowsEveryLineWithoutGaps() {
		String snippet = codebase.snippets(List.of(new SnippetRef(project, ORDER, 33, 53))).getFirst();

		assertThat(snippet).isEqualTo(lines(33, 53)).doesNotContain("…").contains("updatable = false");
	}

	@Test
	void aRefOnTheFakeSecretShowsTheRedactedText() {
		String snippet = codebase.snippets(List.of(new SnippetRef(project, CONFIG, 7, 8))).getFirst();

		assertThat(snippet).contains("password: <REDACTED:").doesNotContain("fixture-pa55word-not-real");
	}

	@Test
	void aRefPastTheLastLineIsCutAtTheLastLine() {
		List<String> snippets = codebase.snippets(List.of(new SnippetRef(project, ORDER, order.size() - 1, 500),
				new SnippetRef(project, ORDER, order.size() + 1, 500)));

		assertThat(snippets.get(0)).isEqualTo(lines(order.size() - 1, order.size()));
		assertThat(snippets.get(1)).isNull();
	}

	/** Files analyzed before V2 have no stored text: same snippets as before, in the same batch and order. */
	@Test
	void filesWithoutStoredTextFallBackToTheChunks() {
		UUID before = fixture.newProject();
		fixture.index(before);
		jdbc.update("UPDATE code_file SET content = NULL WHERE project_id = ?", before);
		List<SnippetRef> beforeRefs = List.of(new SnippetRef(before, ORDER, 48, 53), new SnippetRef(before, ORDER, 20, 25),
				new SnippetRef(before, ORDER, 38, 44));

		List<String> snippets = codebase.snippets(List.of(beforeRefs.get(0), new SnippetRef(project, ORDER, 20, 25),
				beforeRefs.get(1), beforeRefs.get(2), new SnippetRef(UUID.randomUUID(), ORDER, 48, 53)));

		List<String> fromChunks = chunkStore.snippets(beforeRefs);
		assertThat(snippets).containsExactly(fromChunks.get(0), lines(20, 25), fromChunks.get(1), fromChunks.get(2),
				null);
		assertThat(fromChunks.get(0)).isEqualTo(lines(48, 53));   // a METHOD chunk: exact before V2 too
		assertThat(fromChunks.get(1)).isNull();                    // header-only: the old problem, kept for old files
	}

	/** Lines {@code from..to} of the fixture's Order.java, as a snippet shows them. */
	private String lines(int from, int to) {
		return String.join("\n", order.subList(from - 1, to));
	}

}
