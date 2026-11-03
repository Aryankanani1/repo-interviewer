package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.RecordingRetryListener;
import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import com.aryankanani.repointerviewer.common.messaging.InvalidEventException;
import com.aryankanani.repointerviewer.common.messaging.OutboxPump;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.llm.LlmOutputException;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 2.4 acceptance tests: retries with backoff and the dead-letter topic, on a real broker, with the fake model.
 * Retries wait 100, 200, 400 ms in Kafka tests; {@link RecordingRetryListener} shows each failed delivery.
 */
@KafkaIntegrationTest
class AnalysisRetryTest {

	private static final String MODEL_UNAVAILABLE = new LlmUnavailableException(new RuntimeException()).getMessage();

	private final ProjectService projectService;

	private final ProjectRepository projects;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final RecordingRetryListener retries;

	private final KafkaOperations<String, String> kafka;

	private final KafkaAdmin kafkaAdmin;

	private final JsonMapper json;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	AnalysisRetryTest(@Autowired ProjectService projectService, @Autowired ProjectRepository projects,
			@Autowired FakeLlmGateway llm, @Autowired FakeEmbeddingModel embeddingModel,
			@Autowired RecordingRetryListener retries, @Autowired KafkaOperations<String, String> kafka,
			@Autowired KafkaAdmin kafkaAdmin, @Autowired JsonMapper json, @Autowired JdbcTemplate jdbc,
			@Autowired ApplicationContext context) {
		this.projectService = projectService;
		this.projects = projects;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.retries = retries;
		this.kafka = kafka;
		this.kafkaAdmin = kafkaAdmin;
		this.json = json;
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
		retries.reset();
	}

	/** Acceptance criterion: a forced LlmUnavailableException retries 3 times, then lands on the DLT. */
	@Test
	void anUnavailableModelIsRetriedThreeTimesThenTheEventGoesToTheDlt() {
		IntStream.range(0, 4).forEach(i -> llm.enqueueFailure(LlmPurpose.SUMMARY,
				new LlmUnavailableException(new RuntimeException("model down"))));
		UUID id = submit();

		Project project = analyze(id);

		assertThat(llm.calls(LlmPurpose.SUMMARY)).isEqualTo(4);   // the first delivery and 3 retries
		assertThat(retries.failedAttempts(Topics.EMBEDDED, id)).containsExactly(1, 2, 3, 4);
		assertThat(retries.deadLettered(Topics.EMBEDDED, id)).isEqualTo(1);
		ConsumerRecord<String, String> dead = deadLetter(Topics.EMBEDDED, id.toString());
		assertThat(json.readValue(dead.value(), EventEnvelope.class).eventId()).isEqualTo(eventId(id, "Embedded"));
		assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.EMBEDDED);
		// Marked FAILED by AnalysisRetryListener with the model's own message, not left in SUMMARIZING.
		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.getFailureReason()).isEqualTo(MODEL_UNAVAILABLE);
		assertThat(processedBy(id)).doesNotContain(SummaryConsumer.NAME);
	}

	/** A retry is worth it: the model is back for the third delivery, and the analysis finishes. */
	@Test
	void aModelThatComesBackDuringTheRetriesStillGivesReady() {
		IntStream.range(0, 2).forEach(i -> llm.enqueueFailure(LlmPurpose.SUMMARY,
				new LlmUnavailableException(new RuntimeException("model down"))));
		UUID id = submit();

		Project project = analyze(id);

		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.READY);
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isEqualTo(3);
		assertThat(retries.failedAttempts(Topics.EMBEDDED, id)).containsExactly(1, 2);
		assertThat(retries.deadLettered(Topics.EMBEDDED, id)).isZero();
		assertThat(processedBy(id)).contains(SummaryConsumer.NAME);
	}

	/** Acceptance criterion: a validation error (a message no consumer can read) goes straight to the DLT. */
	@Test
	void aMalformedEventGoesStraightToTheDlt() throws Exception {
		String key = UUID.randomUUID().toString();
		kafka.send(Topics.EMBEDDED, key, "{\"not\": \"an event\"").get();

		ConsumerRecord<String, String> dead = deadLetter(Topics.EMBEDDED, key);

		assertThat(dead.value()).isEqualTo("{\"not\": \"an event\"");
		assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.EMBEDDED);
		assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
				.isEqualTo(InvalidEventException.class.getName());
		assertThat(retries.failedAttempts(Topics.EMBEDDED, key)).containsExactly(1);   // not delivered again
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isZero();
	}

	/** An unknown version is a validation error too: kept on the DLT, not skipped as in task 2.3. */
	@Test
	void anEventOfAnUnknownVersionGoesStraightToTheDlt() throws Exception {
		String key = UUID.randomUUID().toString();
		String event = """
				{"eventId": "%s", "type": "Embedded", "version": 2, "occurredAt": "2026-11-02T12:00:00Z",
				 "aggregateId": "%s", "payload": {"projectId": "%s"}}""".formatted(UUID.randomUUID(), key, key);
		kafka.send(Topics.EMBEDDED, key, event).get();

		ConsumerRecord<String, String> dead = deadLetter(Topics.EMBEDDED, key);

		assertThat(dead.value()).isEqualTo(event);
		assertThat(retries.failedAttempts(Topics.EMBEDDED, key)).containsExactly(1);
	}

	/** A reply the model would get wrong again is not retried: FAILED at once, one call, nothing on the DLT. */
	@Test
	void anUnusableModelAnswerFailsAtOnceWithoutRetries() {
		LlmOutputException unusable = new LlmOutputException(new RuntimeException("no JSON"));
		llm.enqueueFailure(LlmPurpose.SUMMARY, unusable);
		UUID id = submit();

		Project project = analyze(id);

		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.getFailureReason()).isEqualTo(unusable.getMessage());
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isEqualTo(1);
		assertThat(retries.failedAttempts(Topics.EMBEDDED, id)).isEmpty();
		assertThat(retries.deadLettered(Topics.EMBEDDED, id)).isZero();
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

	/** Reads {@code topic}'s DLT from the start until a record with this key arrives (other tests' records too). */
	private ConsumerRecord<String, String> deadLetter(String topic, String key) {
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				kafkaAdmin.getConfigurationProperties().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			List<TopicPartition> partitions = IntStream.range(0, 3)
					.mapToObj(partition -> new TopicPartition(Topics.dlt(topic), partition)).toList();
			consumer.assign(partitions);
			consumer.seekToBeginning(partitions);
			List<ConsumerRecord<String, String>> found = new ArrayList<>();
			await().atMost(Duration.ofSeconds(30)).until(() -> {
				consumer.poll(Duration.ofMillis(200)).forEach(record -> {
					if (key.equals(record.key())) {
						found.add(record);
					}
				});
				return !found.isEmpty();
			});
			return found.getFirst();
		}
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}

	private UUID eventId(UUID projectId, String type) {
		return jdbc.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id = ? AND type = ?", UUID.class,
				projectId, type);
	}

	private List<String> processedBy(UUID id) {
		return jdbc.queryForList("""
				SELECT p.consumer FROM processed_event p JOIN outbox_event e ON e.id = p.event_id
				WHERE e.aggregate_id = ?
				""", String.class, id);
	}

}
