package com.aryankanani.repointerviewer.codebase.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.RetrievedChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.6: embedding the fixture chunks in batches, then searching them. */
@IntegrationTest
class EmbeddingServiceTest {

	private final EmbeddingService embeddingService;

	private final ChunkStore chunkStore;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	EmbeddingServiceTest(@Autowired EmbeddingService embeddingService, @Autowired ChunkStore chunkStore,
			@Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc) {
		this.embeddingService = embeddingService;
		this.chunkStore = chunkStore;
		this.fixture = fixture;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void deleteProjects() {
		jdbc.update("DELETE FROM project");   // cascades to code_file and code_chunk
	}

	/**
	 * Acceptance criterion. The fixture has two refund methods. The bare word ranks the 4-line controller
	 * method first, because it is almost only that word. A topic sentence, which is what the question planner
	 * will search with (task 1.10), ranks the service method with the business rule first.
	 */
	@Test
	void refundSearchesFindTheRefundMethods() {
		UUID project = fixture.newProject();
		int chunks = fixture.index(project).size();

		assertThat(embeddingService.embedPending(project)).isEqualTo(chunks);

		assertThat(search(project, "refund").getFirst().symbol()).isEqualTo("OrderController#refund");
		assertThat(search(project, "refund a paid order").getFirst().symbol()).isEqualTo("OrderService#refund");
	}

	@Test
	void embedsInBatchesUntilNothingIsLeft() {
		UUID project = fixture.newProject();
		UUID otherProject = fixture.newProject();
		int chunks = fixture.index(project).size();
		fixture.index(otherProject);
		FakeEmbeddingModel model = new FakeEmbeddingModel();
		EmbeddingService service = new EmbeddingService(chunkStore, model, 5, FakeEmbeddingModel.DIMENSIONS);

		assertThat(service.embedPending(project)).isEqualTo(chunks);

		int batches = (chunks + 4) / 5;
		assertThat(model.calls()).isEqualTo(batches);
		assertThat(unembedded(project)).isZero();
		assertThat(unembedded(otherProject)).isEqualTo(chunks);
		// A second run finds nothing left and does not call the model.
		assertThat(service.embedPending(project)).isZero();
		assertThat(model.calls()).isEqualTo(batches);
	}

	@Test
	void aFailedRunResumesWhereItStopped() {
		UUID project = fixture.newProject();
		int chunks = fixture.index(project).size();
		FakeEmbeddingModel failsOnSecondBatch = new FakeEmbeddingModel();
		failsOnSecondBatch.failOnCall(2);

		assertThatThrownBy(() -> new EmbeddingService(chunkStore, failsOnSecondBatch, 5,
				FakeEmbeddingModel.DIMENSIONS).embedPending(project))
				.hasMessageContaining("Simulated embedding failure");
		assertThat(unembedded(project)).isEqualTo(chunks - 5);   // the first batch stayed saved

		EmbeddingService restarted = new EmbeddingService(chunkStore, new FakeEmbeddingModel(), 5,
				FakeEmbeddingModel.DIMENSIONS);
		assertThat(restarted.embedPending(project)).isEqualTo(chunks - 5);
		assertThat(unembedded(project)).isZero();
	}

	@Test
	void aModelWithOtherDimensionsFailsBeforeWritingAnything() {
		UUID project = fixture.newProject();
		int chunks = fixture.index(project).size();
		EmbeddingService service = new EmbeddingService(chunkStore, new FakeEmbeddingModel(384), 5,
				FakeEmbeddingModel.DIMENSIONS);

		assertThatThrownBy(() -> service.embedPending(project)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("returned 384 dimensions").hasMessageContaining("expect 768");
		assertThat(unembedded(project)).isEqualTo(chunks);
		assertThatThrownBy(() -> service.embedQuery("refund")).isInstanceOf(IllegalStateException.class);
	}

	private List<RetrievedChunk> search(UUID project, String text) {
		return chunkStore.search(project, embeddingService.embedQuery(text), 8);
	}

	private int unembedded(UUID project) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunk WHERE project_id = ? AND embedding IS NULL",
				Integer.class, project);
	}

}
