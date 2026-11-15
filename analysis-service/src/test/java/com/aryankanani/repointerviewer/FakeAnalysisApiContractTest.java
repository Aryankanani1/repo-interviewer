package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkRangesRequest;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkSearchRequest;
import com.aryankanani.repointerviewer.contracts.analysis.LineRange;
import com.aryankanani.repointerviewer.contracts.analysis.RetrievedChunk;
import com.aryankanani.repointerviewer.contracts.analysis.RetrievedContext;
import com.aryankanani.repointerviewer.contracts.analysis.SnippetRef;
import com.aryankanani.repointerviewer.contracts.analysis.TopicQuery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code FakeAnalysisApi} (libs/test-support), which the other services' tests use instead of this service, gives the
 * same answers as the real {@link AnalysisApi} for the fixture repository (task 4.1). Chunks are compared without
 * their ids and similarity, which only the database has. A search is compared on its exact hits: the fake has no
 * vectors, so what comes after them differs on purpose.
 */
@IntegrationTest
class FakeAnalysisApiContractTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private static final int NO_LIMIT = 100_000;

	private final AnalysisApi real;

	private final FixtureIndexer fixtures;

	private final ChunkStore chunkStore;

	private final FakeAnalysisApi fake = new FakeAnalysisApi();

	private UUID project;

	FakeAnalysisApiContractTest(@Autowired AnalysisApi real, @Autowired FixtureIndexer fixtures,
			@Autowired ChunkStore chunkStore) {
		this.real = real;
		this.fixtures = fixtures;
		this.chunkStore = chunkStore;
	}

	@BeforeEach
	void readyProject() {
		project = fixtures.readyProject();
	}

	@Test
	void sameCodeNames() {
		assertThat(fake.codeNames(UUID.randomUUID())).isEqualTo(real.codeNames(project));
	}

	@Test
	void sameChunksForLineRanges() {
		List<LineRange> ranges = List.of(
				new LineRange(ORDER + "OrderService.java", 27, 42),   // a method
				new LineRange(ORDER + "Order.java", 1, 3),            // only the type header covers it
				new LineRange("src/main/resources/application.yml", 1, 4),
				new LineRange("src/Ghost.java", 1, 9));
		for (int budget : List.of(NO_LIMIT, 300, 1)) {   // the token cut too: a big chunk skipped, at least one kept
			ChunkRangesRequest request = new ChunkRangesRequest(ranges, budget);
			assertThat(withoutIds(fake.chunksForRanges(UUID.randomUUID(), request)))
					.as("budget %d", budget).isEqualTo(withoutIds(real.chunksForRanges(project, request)));
		}
	}

	@Test
	void sameExactHitsFirstInASearch() {
		List<String> paths = List.of(ORDER + "OrderController.java");
		List<String> symbols = List.of("OrderService#refund", "Order", "RefundRequest");
		List<RetrievedChunk> exactHits = chunkStore.exactHits(project, paths, symbols);
		RetrievedContext searched = fake.searchChunks(UUID.randomUUID(),
				new ChunkSearchRequest(new TopicQuery("Refunds", paths, symbols, false), NO_LIMIT));

		assertThat(exactHits).isNotEmpty();
		assertThat(withoutIds(searched.chunks().subList(0, exactHits.size()))).isEqualTo(withoutIds(exactHits));
	}

	@Test
	void sameSnippets() {
		List<SnippetRef> refs = List.of(new SnippetRef(project, ORDER + "Order.java", 14, 25),
				new SnippetRef(project, ORDER + "OrderService.java", 27, 999),   // cut at the last line
				new SnippetRef(project, ORDER + "OrderService.java", 999, 1000),
				new SnippetRef(project, "src/Ghost.java", 1, 2));
		assertThat(fake.snippets(refs)).isEqualTo(real.snippets(refs));
	}

	/** What a chunk says, without what only the database assigns. */
	private record Seen(String path, String symbol, int startLine, int endLine, String content, int tokens) {
	}

	private static List<Seen> withoutIds(RetrievedContext context) {
		return withoutIds(context.chunks());
	}

	private static List<Seen> withoutIds(List<RetrievedChunk> chunks) {
		return chunks.stream().map(chunk -> new Seen(chunk.path(), chunk.symbol(), chunk.startLine(),
				chunk.endLine(), chunk.content(), chunk.tokenEstimate())).toList();
	}

}
