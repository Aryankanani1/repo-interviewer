package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.InterviewServiceApplication;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.TestDoublesConfiguration;
import com.aryankanani.repointerviewer.TestcontainersConfiguration;
import com.aryankanani.repointerviewer.contracts.interview.AnswerFlowEvent;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.outbox.OutboxPump;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 2.6 acceptance test, interview side: two interview-service instances on one broker and one database. Instance
 * A is this test's context; instance B is a second, non-web Spring context started here on A's containers, with the
 * same test doubles. Their worker groups share the partitions, so an answer is graded once, by either one. That each
 * GraphQL instance has a fan-out group of its own is the app's {@code TwoInstancesTest} (task 4.1).
 */
@KafkaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)   // one instance B for the class: a context takes seconds to start
class TwoInstancesTest {

	private final InterviewApi interviews;

	private final FakeAnalysisApi fixture;

	private final FakeLlmGateway llm;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	private ConfigurableApplicationContext instanceB;

	TwoInstancesTest(@Autowired InterviewApi interviews, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeLlmGateway llm, @Autowired JdbcTemplate jdbc, @Autowired ApplicationContext context) {
		this.interviews = interviews;
		this.fixture = fixture;
		this.llm = llm;
		this.jdbc = jdbc;
		this.context = context;
	}

	@BeforeAll
	void startInstanceB() {
		PostgreSQLContainer postgres = context.getBean(PostgreSQLContainer.class);
		KafkaContainer kafka = context.getBean(KafkaContainer.class);
		instanceB = new SpringApplicationBuilder(InterviewServiceApplication.class, TestDoublesConfiguration.class)
				.web(WebApplicationType.NONE)
				.profiles("test", "kafka")
				.initializers(b -> b.getBeanFactory().registerSingleton("skipTestClasses", new SkipTestClasses()))
				// Command-line arguments, so they win over application.yml (default properties would not).
				.run("--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + TestcontainersConfiguration.SERVICE_USER,
						"--spring.datasource.password=" + TestcontainersConfiguration.SERVICE_USER,
						"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
						"--app.outbox.poll-interval=1h", "--app.consumer-retry.initial-backoff=100ms");
	}

	@AfterAll
	void stopInstanceB() {
		if (instanceB != null) {
			instanceB.close();
		}
	}

	@Test
	void bothInstancesJoinTheSameWorkerGroups() {
		assertThat(groupIds(instanceB)).contains(AnswerEvaluationConsumer.NAME, InterviewOrchestrator.NAME);
		assertThat(groupIds(context)).contains(AnswerEvaluationConsumer.NAME, InterviewOrchestrator.NAME);
	}

	/** Exactly one instance grades the answer (the worker group shares it), and the interview goes on. */
	@Test
	void anAnswerIsGradedOnceByEitherInstance() {
		jdbc.update("DELETE FROM interview_session");   // cascades to topics, questions and answers
		fixture.reset();
		llm.reset();
		FakeLlmGateway llmB = instanceB.getBean(FakeLlmGateway.class);
		llmB.reset();
		StartResponse started = interviews.start(new StartInterviewRequest(fixture.readyProject(), null, null, 3));
		UUID sessionId = started.session().id();

		assertThat(interviews.submitAnswer(started.firstQuestion().id(),
				new AnswerRequest("Both read the same state.")).error()).isNull();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			OutboxPump.publishPending(context);
			return questionReadySent(sessionId);
		});

		assertThat(llm.calls(LlmPurpose.EVALUATION) + llmB.calls(LlmPurpose.EVALUATION)).isEqualTo(1);
		assertThat(interviews.askedQuestions(List.of(sessionId))).hasSize(2);
	}

	private boolean questionReadySent(UUID sessionId) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND type = ? AND published_at IS NOT NULL
				""", Integer.class, sessionId, AnswerFlowEvent.QUESTION_READY) == 1;
	}

	private static List<String> groupIds(ApplicationContext instance) {
		return instance.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream()
				.map(container -> container.getGroupId()).toList();
	}

	/**
	 * Spring Boot's tests keep test classes out of the component scan with a filter of their own (package-private);
	 * instance B is a plain Spring application, so it gets this one. Everything compiled from src/test is skipped,
	 * like the configuration that would start a second Postgres container; B gets the test doubles as a source.
	 */
	static final class SkipTestClasses extends TypeExcludeFilter {

		@Override
		public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
			return reader.getResource().getURL().getPath().contains("/test-classes/");
		}

	}

}
