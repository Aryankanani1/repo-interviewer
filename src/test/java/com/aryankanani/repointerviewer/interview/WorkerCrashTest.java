package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeModelServer;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.WorkerProcess;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 2.7 acceptance test: a worker killed in the middle of the EVALUATION call, then restarted. The workers are
 * real app processes ({@link WorkerProcess}) with the real {@code SpringAiLlmGateway}, budget and {@code llm_call}
 * ledger, talking to {@link FakeModelServer} instead of Anthropic and Ollama; this test's own context starts the
 * interview and takes the answer, and its listeners are stopped meanwhile, so only the worker processes consume.
 *
 * <p>About 40 s: two app starts, and the broker waits for the killed member's session timeout (6 s here, 45 s by
 * default) before it gives the partitions to the restarted one. Worker output: {@code target/worker-crash-*.log}.
 */
@KafkaIntegrationTest
class WorkerCrashTest {

	private final GraphQlTester graphQlTester;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final KafkaAdmin kafkaAdmin;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	private final List<WorkerProcess> workers = new ArrayList<>();

	private FakeModelServer models;

	WorkerCrashTest(@Autowired GraphQlTester graphQlTester, @Autowired FixtureIndexer fixture,
			@Autowired FakeLlmGateway llm, @Autowired KafkaAdmin kafkaAdmin, @Autowired JdbcTemplate jdbc,
			@Autowired ApplicationContext context) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.llm = llm;
		this.kafkaAdmin = kafkaAdmin;
		this.jdbc = jdbc;
		this.context = context;
	}

	@BeforeEach
	void setUp() throws Exception {
		jdbc.update("DELETE FROM project");   // cascades to files, chunks and sessions
		jdbc.update("DELETE FROM outbox_event");
		jdbc.update("DELETE FROM processed_event");
		llm.reset();
		context.getBean(KafkaListenerEndpointRegistry.class).stop();   // this JVM consumes nothing during the test
		models = new FakeModelServer();
	}

	@AfterEach
	void tearDown() throws Exception {
		for (WorkerProcess worker : workers) {
			worker.close();
		}
		models.close();
		context.getBean(KafkaListenerEndpointRegistry.class).start();
	}

	/** Acceptance criterion: the answer is evaluated exactly once, with one successful EVALUATION llm_call row. */
	@Test
	void aWorkerKilledDuringTheEvaluationCallIsRestartedAndTheAnswerIsGradedOnce() throws Exception {
		models.holdFirstEvaluation();
		String sessionId = startInterview();
		String questionId = graphQlTester
				.document("query ($id: ID!) { interviewSession(id: $id) { currentQuestion { id } } }")
				.variable("id", sessionId).execute().path("interviewSession.currentQuestion.id").entity(String.class)
				.get();
		WorkerProcess first = startWorker("worker-1");
		awaitEvaluationWorkerJoined();

		// The worker's outbox relay sends AnswerSubmitted, and its evaluation consumer calls the model.
		graphQlTester.document("""
				mutation ($input: SubmitAnswerInput!) { submitAnswer(input: $input) { errors { code } } }
				""").variable("input", Map.of("questionId", questionId, "text", "Both read the same refundedAmount."))
				.execute().path("submitAnswer.errors").entityList(Object.class).hasSize(0);
		assertThat(models.awaitEvaluationRequest(60)).as("the worker is calling the model").isTrue();

		first.kill();   // SIGKILL mid-call: nothing graded, nothing written to the ledger, no offset committed
		models.release();
		assertThat(first.isAlive()).isFalse();
		assertThat(evaluationCalls(sessionId)).isEmpty();
		assertThat(evaluatedAt(questionId)).isNull();

		startWorker("worker-2");   // the restart: Kafka delivers the same AnswerSubmitted again
		await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500))
				.until(() -> events(sessionId, KafkaAnswerPipeline.QUESTION_READY) == 1);

		List<Map<String, Object>> calls = evaluationCalls(sessionId);
		assertThat(calls).hasSize(1);
		assertThat(calls.getFirst()).containsEntry("success", true).containsEntry("error_type", null);
		assertThat(jdbc.queryForObject("SELECT score FROM answer WHERE question_id = ?::uuid", Integer.class,
				questionId)).isEqualTo(9);
		assertThat(evaluatedAt(questionId)).isNotNull();
		assertThat(events(sessionId, KafkaAnswerPipeline.ANSWER_EVALUATED)).isEqualTo(1);
		assertThat(models.evaluationRequests()).as("one killed, one answered").isEqualTo(2);
		assertThat(models.otherRequests()).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM processed_event p JOIN outbox_event e ON e.id = p.event_id
				WHERE e.aggregate_id = ?::uuid AND p.consumer = ?
				""", Integer.class, sessionId, AnswerEvaluationConsumer.NAME)).isEqualTo(1);
	}

	private String startInterview() {
		String project = fixture.readyProject().toString();
		return graphQlTester.document("""
				mutation ($input: StartInterviewInput!) { startInterview(input: $input) { session { id } } }
				""").variable("input", Map.of("projectId", project, "questionCount", 3)).execute()
				.path("startInterview.session.id").entity(String.class).get();
	}

	private WorkerProcess startWorker(String name) throws Exception {
		PostgreSQLContainer postgres = context.getBean(PostgreSQLContainer.class);
		KafkaContainer kafka = context.getBean(KafkaContainer.class);
		WorkerProcess worker = WorkerProcess.start(List.of(
				"--spring.profiles.active=dev,kafka",
				"--server.port=0",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
				"--spring.kafka.consumer.properties.session.timeout.ms=6000",
				"--spring.kafka.consumer.properties.heartbeat.interval.ms=1000",
				"--spring.ai.anthropic.base-url=" + models.baseUrl(),
				"--spring.ai.anthropic.api-key=test-key-not-used",
				"--spring.ai.anthropic.max-retries=0",
				"--spring.ai.ollama.base-url=" + models.baseUrl(),
				"--app.interview.prefetch-enabled=false",
				"--logging.level.com.aryankanani.repointerviewer=INFO"),
				Path.of("target", "worker-crash-" + name + ".log"));
		workers.add(worker);
		return worker;
	}

	/** Until a member of the evaluation group holds all three partitions of its topic. */
	private void awaitEvaluationWorkerJoined() {
		Object bootstrap = kafkaAdmin.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
		try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
			await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() -> {
				ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(AnswerEvaluationConsumer.NAME))
						.all().get().get(AnswerEvaluationConsumer.NAME);
				return group.members().stream().anyMatch(member -> member.assignment().topicPartitions().stream()
						.filter(partition -> partition.topic().equals(Topics.ANSWER_SUBMITTED)).count() == 3);
			});
		}
	}

	private List<Map<String, Object>> evaluationCalls(String sessionId) {
		return jdbc.queryForList(
				"SELECT success, error_type FROM llm_call WHERE session_id = ?::uuid AND purpose = 'EVALUATION'",
				sessionId);
	}

	private Object evaluatedAt(String questionId) {
		return jdbc.queryForObject("SELECT evaluated_at FROM answer WHERE question_id = ?::uuid", Object.class,
				questionId);
	}

	private int events(String sessionId, String type) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid AND type = ?",
				Integer.class, sessionId, type);
	}

}
