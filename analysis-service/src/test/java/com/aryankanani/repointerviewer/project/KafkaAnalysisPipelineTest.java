package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.KafkaConsumerLag;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.common.messaging.OutboxPump;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Task 2.3 acceptance tests: the fixture repository analyzed through Kafka, one consumer per stage, on a real broker
 * (Testcontainers), with the fake model and embeddings. The relay's timer is slowed in Kafka tests, so
 * {@link #analyze} pumps the outbox until the project is done. Failures and retries: {@link AnalysisRetryTest}.
 */
@KafkaIntegrationTest
class KafkaAnalysisPipelineTest {

	/** Each stage's consumer group and the topic it reads. */
	private static final Map<String, String> GROUPS = Map.of(IngestConsumer.NAME, Topics.ANALYSIS_REQUESTED,
			EmbedConsumer.NAME, Topics.CHUNKS_READY, SummaryConsumer.NAME, Topics.EMBEDDED);

	private final ProjectService projectService;

	private final ProjectRepository projects;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final KafkaAdmin kafkaAdmin;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	KafkaAnalysisPipelineTest(@Autowired ProjectService projectService, @Autowired ProjectRepository projects,
			@Autowired FakeLlmGateway llm, @Autowired FakeEmbeddingModel embeddingModel,
			@Autowired KafkaAdmin kafkaAdmin, @Autowired JdbcTemplate jdbc, @Autowired ApplicationContext context) {
		this.projectService = projectService;
		this.projects = projects;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.kafkaAdmin = kafkaAdmin;
		this.jdbc = jdbc;
		this.context = context;
	}

	@BeforeEach
	void cleanState() {
		jdbc.update("DELETE FROM project");   // cascades to files, chunks and sessions
		jdbc.update("DELETE FROM outbox_event");
		jdbc.update("DELETE FROM processed_event");
		llm.reset();
		embeddingModel.reset();
	}

	/** Acceptance criterion: the fixture repo reaches READY through Kafka. */
	@Test
	void theFixtureRepositoryReachesReadyThroughKafka() {
		UUID id = submit();
		// Written in submit's own transaction, before anything was sent.
		assertThat(eventTypes(id)).containsExactly(KafkaAnalysisPipeline.ANALYSIS_REQUESTED);

		Project project = analyze(id);

		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.READY);
		assertThat(project.getSummary()).isNotNull();
		assertThat(project.getChunkCount()).isPositive().isEqualTo(chunks(id));
		assertThat(project.getBuildFiles()).startsWith("pom.xml");
		assertThat(project.getReadme()).isNotBlank();
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isEqualTo(1);
		// The summary consumer read what the ingest consumer stored: no in-memory hand-off between JVMs.
		assertThat(llm.requests().getFirst().variables()).containsEntry("buildFiles", project.getBuildFiles())
				.containsEntry("readme", project.getReadme());
		assertThat(eventTypes(id)).containsExactly(KafkaAnalysisPipeline.ANALYSIS_REQUESTED,
				KafkaAnalysisPipeline.CHUNKS_READY, KafkaAnalysisPipeline.EMBEDDED);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND published_at IS NULL",
				Integer.class, id)).isZero();
		assertThat(consumersThatProcessed(id)).containsExactlyInAnyOrderElementsOf(GROUPS.keySet());
	}

	/**
	 * Acceptance criterion: delivering the same events again changes nothing. The relay sends all three events of a
	 * finished analysis a second time (same event ids, as after a relay crash before it marked them); the test waits
	 * until every consumer group has committed past them, so each duplicate was read and skipped.
	 */
	@Test
	void redeliveringEveryEventOfAFinishedAnalysisChangesNothing() {
		UUID id = submit();
		analyze(id);
		int chunks = chunks(id);
		int embeddingCalls = embeddingModel.calls();

		jdbc.update("UPDATE outbox_event SET published_at = NULL WHERE aggregate_id = ?", id);
		assertThat(OutboxPump.publishPending(context)).hasSize(3);
		awaitConsumersCaughtUp();

		assertThat(chunks(id)).isEqualTo(chunks);
		assertThat(embeddingModel.calls()).isEqualTo(embeddingCalls);
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isEqualTo(1);
		assertThat(eventTypes(id)).hasSize(3);   // no stage wrote its next event again
		assertThat(consumersThatProcessed(id)).hasSize(3);
		assertThat(projects.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.READY);
	}

	@Test
	void theKafkaProfileReplacesTheInProcessPipeline() {
		assertThat(context.getBeanNamesForType(InProcessAnalysisPipeline.class)).isEmpty();
		assertThat(context.getBeanNamesForType(AnalysisStarter.class)).isEmpty();
		assertThat(context.getBeanNamesForType(KafkaAnalysisPipeline.class)).hasSize(1);
	}

	private UUID submit() {
		return projectService.submit(new SubmitProjectCommand("https://github.com/acme/shop-" + UUID.randomUUID(),
				"Learn Spring Boot", null, null)).getId();
	}

	/** Pumps the outbox until the project is READY or FAILED. */
	private Project analyze(UUID id) {
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			OutboxPump.publishPending(context);
			AnalysisStatus status = projects.findById(id).orElseThrow().getStatus();
			return status == AnalysisStatus.READY || status == AnalysisStatus.FAILED;
		});
		return projects.findById(id).orElseThrow();
	}

	/** Until each stage's group has committed an offset at the end of every partition of its topic. */
	private void awaitConsumersCaughtUp() {
		KafkaConsumerLag.awaitCaughtUp(kafkaAdmin, GROUPS);
	}

	private int chunks(UUID id) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunk WHERE project_id = ?", Integer.class, id);
	}

	private List<String> eventTypes(UUID id) {
		return jdbc.queryForList("SELECT type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, id",
				String.class, id);
	}

	private List<String> consumersThatProcessed(UUID id) {
		return jdbc.queryForList("""
				SELECT p.consumer FROM processed_event p JOIN outbox_event e ON e.id = p.event_id
				WHERE e.aggregate_id = ?
				""", String.class, id);
	}

}
