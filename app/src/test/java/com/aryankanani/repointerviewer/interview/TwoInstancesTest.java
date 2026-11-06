package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.KafkaListeners;
import com.aryankanani.repointerviewer.RepoInterviewerApplication;
import com.aryankanani.repointerviewer.TestDoublesConfiguration;
import com.aryankanani.repointerviewer.common.messaging.OutboxPump;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.Disposable;

/**
 * Task 2.6 acceptance test: two app instances on one broker and one database. Instance A is this test's context;
 * instance B is a second, non-web Spring context started here on A's containers, with the same test doubles. Both run
 * every worker (their groups share the partitions) and their own fan-out group.
 */
@KafkaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)   // one instance B for the class: a context takes seconds to start
class TwoInstancesTest {

	private static final String SUBSCRIBE = """
			subscription ($id: ID!) {
			  interviewEvents(sessionId: $id) { type nextQuestion { id } }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	private ConfigurableApplicationContext instanceB;

	private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

	TwoInstancesTest(@Autowired GraphQlTester graphQlTester, @Autowired FixtureIndexer fixture,
			@Autowired FakeLlmGateway llm, @Autowired JdbcTemplate jdbc, @Autowired ApplicationContext context) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.llm = llm;
		this.jdbc = jdbc;
		this.context = context;
	}

	@BeforeAll
	void startInstanceB() {
		PostgreSQLContainer postgres = context.getBean(PostgreSQLContainer.class);
		KafkaContainer kafka = context.getBean(KafkaContainer.class);
		instanceB = new SpringApplicationBuilder(RepoInterviewerApplication.class, TestDoublesConfiguration.class)
				.web(WebApplicationType.NONE)
				.profiles("test", "kafka")
				.initializers(b -> b.getBeanFactory().registerSingleton("skipTestClasses", new SkipTestClasses()))
				// Command-line arguments, so they win over application.yml (default properties would not).
				.run("--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword(),
						"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
						"--app.outbox.poll-interval=1h", "--app.consumer-retry.initial-backoff=100ms");
	}

	@AfterAll
	void stopInstanceB() {
		if (instanceB != null) {
			instanceB.close();
		}
	}

	@AfterEach
	void unsubscribe() {
		subscriptions.forEach(Disposable::dispose);
		subscriptions.clear();
	}

	@Test
	void eachInstanceHasItsOwnFanOutGroupButSharesTheWorkerGroups() {
		assertThat(groupId(instanceB, InterviewEventsFanOut.LISTENER_ID))
				.startsWith("interview-events-")
				.isNotEqualTo(groupId(context, InterviewEventsFanOut.LISTENER_ID));
		assertThat(groupIds(instanceB)).contains(AnswerEvaluationConsumer.NAME, InterviewOrchestrator.NAME);
		assertThat(groupIds(context)).contains(AnswerEvaluationConsumer.NAME, InterviewOrchestrator.NAME);
	}

	/**
	 * Acceptance criterion: a client connected to one instance gets the events of work another instance's worker did.
	 * Exactly one instance grades the answer (the worker group shares it), and the clients on both instances get the
	 * grade and the next question, so the one on the instance that did not do the work does too.
	 */
	@Test
	void aClientOnEitherInstanceGetsTheEventsOfWorkDoneByEither() {
		KafkaListeners.awaitAssigned(context, InterviewEventsFanOut.LISTENER_ID, 6);
		KafkaListeners.awaitAssigned(instanceB, InterviewEventsFanOut.LISTENER_ID, 6);
		jdbc.update("DELETE FROM project");
		llm.reset();
		FakeLlmGateway llmB = instanceB.getBean(FakeLlmGateway.class);
		llmB.reset();
		String sessionId = startInterview();
		List<GraphQlTester.Response> onA = subscribe(graphQlTester, sessionId);
		List<GraphQlTester.Response> onB = subscribe(
				ExecutionGraphQlServiceTester.create(instanceB.getBean(ExecutionGraphQlService.class)), sessionId);

		String firstQuestion = graphQlTester
				.document("query ($id: ID!) { interviewSession(id: $id) { currentQuestion { id } } }")
				.variable("id", sessionId).execute().path("interviewSession.currentQuestion.id").entity(String.class)
				.get();
		graphQlTester.document("""
				mutation ($input: SubmitAnswerInput!) { submitAnswer(input: $input) { errors { code } } }
				""").variable("input", Map.of("questionId", firstQuestion, "text", "Both read the same state."))
				.execute().path("submitAnswer.errors").entityList(Object.class).hasSize(0);
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			OutboxPump.publishPending(context);
			return onA.size() >= 2 && onB.size() >= 2;
		});

		assertThat(llm.calls(LlmPurpose.EVALUATION) + llmB.calls(LlmPurpose.EVALUATION)).isEqualTo(1);
		assertThat(types(onA)).containsExactly("ANSWER_EVALUATED", "QUESTION_READY");
		assertThat(types(onB)).containsExactly("ANSWER_EVALUATED", "QUESTION_READY");
		assertThat(onB.get(1).path("interviewEvents.nextQuestion.id").entity(String.class).get())
				.isEqualTo(onA.get(1).path("interviewEvents.nextQuestion.id").entity(String.class).get());
	}

	private String startInterview() {
		String project = fixture.readyProject().toString();
		return graphQlTester.document("""
				mutation ($input: StartInterviewInput!) { startInterview(input: $input) { session { id } } }
				""").variable("input", Map.of("projectId", project, "questionCount", 3)).execute()
				.path("startInterview.session.id").entity(String.class).get();
	}

	private List<GraphQlTester.Response> subscribe(GraphQlTester tester, String sessionId) {
		List<GraphQlTester.Response> received = new CopyOnWriteArrayList<>();
		subscriptions.add(tester.document(SUBSCRIBE).variable("id", sessionId).executeSubscription().toFlux()
				.subscribe(received::add));
		return received;
	}

	private static String groupId(ApplicationContext instance, String listenerId) {
		return instance.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(listenerId).getGroupId();
	}

	private static List<String> groupIds(ApplicationContext instance) {
		return instance.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream()
				.map(container -> container.getGroupId()).toList();
	}

	private static List<String> types(List<GraphQlTester.Response> events) {
		return events.stream().map(event -> event.path("interviewEvents.type").entity(String.class).get()).toList();
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
