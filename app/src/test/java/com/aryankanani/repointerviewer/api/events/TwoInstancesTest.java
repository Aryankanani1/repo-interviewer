package com.aryankanani.repointerviewer.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.KafkaListeners;
import com.aryankanani.repointerviewer.RepoInterviewerApplication;
import com.aryankanani.repointerviewer.TestDoublesConfiguration;
import com.aryankanani.repointerviewer.common.messaging.AggregateType;
import com.aryankanani.repointerviewer.common.messaging.OutboxPump;
import com.aryankanani.repointerviewer.common.messaging.OutboxWriter;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerFlowEvent;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.Disposable;

/**
 * Task 2.6 acceptance test, GraphQL side (task 4.1): two app instances on one broker. Each has a fan-out group of its
 * own, so a client connected to either one gets every event of its session, whichever interview-service instance did
 * the work. Instance A is this test's context; instance B is a second, non-web Spring context started here on A's
 * containers, with the same test doubles (its own {@code FakeInterviewApi}, given the same records). That the
 * interview workers share their groups is interview-service's {@code TwoInstancesTest}.
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

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	private final OutboxWriter outbox;

	private final TransactionTemplate transaction;

	private final ApplicationContext context;

	private ConfigurableApplicationContext instanceB;

	private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

	TwoInstancesTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeInterviewApi interviews, @Autowired OutboxWriter outbox,
			@Autowired PlatformTransactionManager transactionManager, @Autowired ApplicationContext context) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.interviews = interviews;
		this.outbox = outbox;
		this.transaction = new TransactionTemplate(transactionManager);
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
	void eachInstanceHasItsOwnFanOutGroup() {
		assertThat(groupId(instanceB)).startsWith("interview-events-").isNotEqualTo(groupId(context));
	}

	/** Acceptance criterion: the clients on both instances get the grade and the next question. */
	@Test
	void aClientOnEitherInstanceGetsEveryEventOfItsSession() {
		KafkaListeners.awaitAssigned(context, InterviewEventsFanOut.LISTENER_ID, 6);
		KafkaListeners.awaitAssigned(instanceB, InterviewEventsFanOut.LISTENER_ID, 6);
		fixture.reset();
		FakeInterviewApi interviewsB = instanceB.getBean(FakeInterviewApi.class);
		SessionDto started = FakeInterviewApi.session(fixture.readyProject());
		QuestionDto first = FakeInterviewApi.question(started.id(), 1, null);
		QuestionDto second = FakeInterviewApi.question(started.id(), 2, null);
		AnswerDto answer = FakeInterviewApi.graded(first.id(), "Both read the same state.", 9);
		SessionDto session = FakeInterviewApi.asking(started, second.id());
		for (FakeInterviewApi fake : List.of(interviews, interviewsB)) {   // what interview-service would answer both
			fake.reset();
			fake.add(first);
			fake.add(second);
			fake.add(answer);
			fake.add(session);
		}
		List<GraphQlTester.Response> onA = subscribe(graphQlTester, session.id());
		List<GraphQlTester.Response> onB = subscribe(
				ExecutionGraphQlServiceTester.create(instanceB.getBean(ExecutionGraphQlService.class)), session.id());

		send(session.id(), Topics.ANSWER_EVALUATED, AnswerFlowEvent.ANSWER_EVALUATED,
				new AnswerFlowEvent(answer.id(), first.id(), null));
		send(session.id(), Topics.QUESTION_READY, AnswerFlowEvent.QUESTION_READY,
				new AnswerFlowEvent(answer.id(), first.id(), second.id()));
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			OutboxPump.publishPending(context);
			return onA.size() >= 2 && onB.size() >= 2;
		});

		assertThat(types(onA)).containsExactly("ANSWER_EVALUATED", "QUESTION_READY");
		assertThat(types(onB)).containsExactly("ANSWER_EVALUATED", "QUESTION_READY");
		assertThat(onB.get(1).path("interviewEvents.nextQuestion.id").entity(String.class).get())
				.isEqualTo(second.id().toString());
	}

	private List<GraphQlTester.Response> subscribe(GraphQlTester tester, UUID sessionId) {
		List<GraphQlTester.Response> received = new CopyOnWriteArrayList<>();
		subscriptions.add(tester.document(SUBSCRIBE).variable("id", sessionId).executeSubscription().toFlux()
				.subscribe(received::add));
		return received;
	}

	/** An event as interview-service writes it: an outbox row, sent by the relay. */
	private void send(UUID sessionId, String topic, String type, AnswerFlowEvent payload) {
		transaction.executeWithoutResult(status -> outbox.write(topic, AggregateType.SESSION, sessionId, type,
				payload));
	}

	private static String groupId(ApplicationContext instance) {
		return instance.getBean(KafkaListenerEndpointRegistry.class)
				.getListenerContainer(InterviewEventsFanOut.LISTENER_ID).getGroupId();
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
