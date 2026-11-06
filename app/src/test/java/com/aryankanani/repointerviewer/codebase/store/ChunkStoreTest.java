package com.aryankanani.repointerviewer.codebase.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import com.aryankanani.repointerviewer.codebase.chunk.ChunkType;
import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.6: {@link ChunkStore} against real Postgres with pgvector. Vectors here are made by hand, so the
 * expected order and similarity values are exact; {@code EmbeddingServiceTest} covers the model side.
 */
@IntegrationTest
class ChunkStoreTest {

	private static final int DIMENSIONS = 768;

	private final ChunkStore chunkStore;

	private final CodeFileRepository files;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	ChunkStoreTest(@Autowired ChunkStore chunkStore, @Autowired CodeFileRepository files,
			@Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc) {
		this.chunkStore = chunkStore;
		this.files = files;
		this.fixture = fixture;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void deleteProjects() {
		jdbc.update("DELETE FROM project");   // cascades to code_file and code_chunk
	}

	@Test
	void savesFilesAndChunksExactlyAndWithoutEmbeddings() {
		UUID project = fixture.newProject();
		List<Chunk> chunks = fixture.index(project);

		List<Chunk> stored = jdbc.query("""
				SELECT path, chunk_type, symbol, start_line, end_line, content, token_estimate
				FROM code_chunk WHERE project_id = ?
				""", (row, i) -> new Chunk(row.getString("path"), ChunkType.valueOf(row.getString("chunk_type")),
				row.getString("symbol"), row.getInt("start_line"), row.getInt("end_line"), row.getString("content"),
				row.getInt("token_estimate")), project);
		assertThat(stored).containsExactlyInAnyOrderElementsOf(chunks);
		assertThat(chunkStore.findUnembedded(project, 1000)).hasSize(chunks.size());

		List<CodeFile> savedFiles = files.findByProjectId(project);
		assertThat(savedFiles).extracting(CodeFile::getPath)
				.containsExactlyInAnyOrderElementsOf(LocalDirectoryRepoFetcher.FIXTURE_FILES);
		assertThat(savedFiles).filteredOn(file -> file.getPath().endsWith("/OrderService.java"))
				.singleElement().extracting(CodeFile::getRole).isEqualTo(FileRole.SERVICE);
		// Every chunk points at the code_file row of its own path.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM code_chunk c JOIN code_file f ON f.id = c.file_id
				WHERE c.project_id = ? AND c.path <> f.path
				""", Integer.class, project)).isZero();
	}

	@Test
	void searchRanksByCosineSimilarityAndSkipsChunksWithoutEmbedding() {
		UUID project = fixture.newProject();
		fixture.index(project);
		// Only three chunks get a vector: the same direction as the query, 45 degrees off, and at a right angle.
		chunkStore.saveEmbeddings(List.of(
				new ChunkEmbedding(idOf(project, "OrderService#refund"), unit(0)),
				new ChunkEmbedding(idOf(project, "OrderController#refund"), unit(0, 1)),
				new ChunkEmbedding(idOf(project, "Order"), unit(1))));

		List<RetrievedChunk> results = chunkStore.search(project, unit(0), 10);

		assertThat(results).extracting(RetrievedChunk::symbol)
				.containsExactly("OrderService#refund", "OrderController#refund", "Order");
		assertThat(results.get(0).similarity()).isCloseTo(1.0, within(1e-6));
		assertThat(results.get(1).similarity()).isCloseTo(Math.sqrt(0.5), within(1e-6));   // cos 45°
		assertThat(results.get(2).similarity()).isCloseTo(0.0, within(1e-6));             // cos 90°
		RetrievedChunk refund = results.get(0);
		assertThat(refund.path()).isEqualTo("src/main/java/com/example/shop/order/OrderService.java");
		assertThat(refund.content()).contains("public Order refund(UUID orderId, RefundRequest request)");
		assertThat(refund.startLine()).isLessThan(refund.endLine());
	}

	@Test
	void searchNeverReturnsDocChunks() {
		UUID project = fixture.newProject();
		fixture.index(project);
		embedAll(project, unit(0));

		List<RetrievedChunk> results = chunkStore.search(project, unit(0), 1000);

		assertThat(count(project, "chunk_type = 'DOC'")).isPositive();   // the README is chunked as DOC
		assertThat(results).hasSize(count(project, "chunk_type <> 'DOC'"));
		assertThat(results).extracting(RetrievedChunk::path).doesNotContain("README.md");
	}

	@Test
	void searchStaysInsideTheProject() {
		UUID projectA = fixture.newProject();
		UUID projectB = fixture.newProject();
		fixture.index(projectA);
		fixture.index(projectB);
		// Identical chunks with identical vectors: only the project_id filter can tell them apart.
		embedAll(projectA, unit(0));
		embedAll(projectB, unit(0));

		List<RetrievedChunk> results = chunkStore.search(projectA, unit(0), 1000);

		List<UUID> idsOfA = jdbc.queryForList("SELECT id FROM code_chunk WHERE project_id = ?", UUID.class, projectA);
		assertThat(results).hasSize(count(projectA, "chunk_type <> 'DOC'"));
		assertThat(results).extracting(RetrievedChunk::id).allMatch(idsOfA::contains);
	}

	/** Task 1.13: the cited lines come back as written, in one query for all refs, in the order asked. */
	@Test
	void snippetsAreTheStoredSourceLines() throws Exception {
		UUID project = fixture.newProject();
		fixture.index(project);
		String order = "src/main/java/com/example/shop/order/Order.java";
		List<String> file = Files.readAllLines(LocalDirectoryRepoFetcher.fixtureRoot().resolve(order));

		List<String> snippets = chunkStore.snippets(List.of(
				new SnippetRef(project, order, 48, 53),              // markRefunded: a METHOD chunk holds these lines
				new SnippetRef(project, order, 20, 25),              // fields: only in the condensed TYPE_HEADER
				new SnippetRef(project, order, 38, 44),              // partly stored: gaps become one "…" each
				new SnippetRef(UUID.randomUUID(), order, 48, 53)));  // another project: nothing

		assertThat(snippets.get(0)).isEqualTo(String.join("\n", file.subList(47, 53)));
		assertThat(snippets.get(1)).isNull();
		assertThat(snippets.get(2)).isEqualTo(String.join("\n", "…", file.get(39), file.get(40), "…", file.get(42),
				file.get(43)));
		assertThat(snippets.get(3)).isNull();
	}

	@Test
	void vectorLiteralIsPgvectorTextFormat() {
		assertThat(ChunkStore.toVectorLiteral(new float[] { 0.5f, -1f, 1.0E-5f })).isEqualTo("[0.5,-1.0,1.0E-5]");
	}

	private void embedAll(UUID project, float[] vector) {
		chunkStore.saveEmbeddings(chunkStore.findUnembedded(project, 1000).stream()
				.map(chunk -> new ChunkEmbedding(chunk.id(), vector)).toList());
	}

	private UUID idOf(UUID project, String symbol) {
		return jdbc.queryForObject("SELECT id FROM code_chunk WHERE project_id = ? AND symbol = ?", UUID.class,
				project, symbol);
	}

	private int count(UUID project, String condition) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunk WHERE project_id = ? AND " + condition,
				Integer.class, project);
	}

	/** A vector of length 1 with equal values in the given slots and 0 everywhere else. */
	private static float[] unit(int... slots) {
		float[] vector = new float[DIMENSIONS];
		for (int slot : slots) {
			vector[slot] = (float) (1 / Math.sqrt(slots.length));
		}
		return vector;
	}

}
