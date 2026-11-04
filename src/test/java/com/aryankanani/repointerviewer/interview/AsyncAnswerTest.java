package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.KafkaConsumerLag;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.KafkaListeners;
import com.aryankanani.repointerviewer.RecordingRetryListener;
import com.aryankanani.repointerviewer.common.messaging.OutboxPump;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.llm.LlmOutputException;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import reactor.core.Disposable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 2.5 acceptance tests: answers graded and followed up by Kafka workers, and the {@code interviewEvents}
 * subscription. A real broker, the fake model, and the GraphQL engine; the outbox is pumped by the test.
 */
@KafkaIntegrationTest
class AsyncAnswerTest {

	private static final String SUBSCRIBE = """
			subscription ($id: ID!) {
			  interviewEvents(sessionId: $id) {
			    type
			    answer { text evaluation { score verdict } }
			    nextQuestion { id order isFollowUp codeRefs { path snippet } }
			    session { status failureReason currentQuestion { id } report { overallScore } }
			  }
			}
			""";

	private static final String SUBMIT = """
			mutation ($input: SubmitAnswerInput!) {
			  submitAnswer(input: $input) {
			    answer { text evaluation { score } }
			    nextQuestion { id order }
			    session { status }
			    errors { code }
			  }
			}
			""";

	private static final String SESSION = """
			query ($id: ID!) {
			  interviewSession(id: $id) {
			    status failureReason
			    currentQuestion { id order }
			    questions { order answer { text evaluation { score } } }
			  }
			}
			""";

	private static final String MODEL_UNAVAILABLE = new LlmUnavailableException(new RuntimeException()).getMessage();

	private final GraphQlTester graphQlTester;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final RecordingRetryListener retries;

	private final KafkaAdmin kafkaAdmin;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final ApplicationContext context;

	private String sessionId;

	private String firstQuestionId;

	private Disposable subscription;

	AsyncAnswerTest(@Autowired GraphQlTester graphQlTester, @Autowired FixtureIndexer fixture,
			@Autowired FakeLlmGateway llm, @Autowired FakeEmbeddingModel embeddingModel,
			@Autowired RecordingRetryListener retries, @Autowired KafkaAdmin kafkaAdmin, @Autowired JdbcTemplate jdbc,
			@Autowired JsonMapper json, @Autowired ApplicationContext context) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.retries = retries;
		this.kafkaAdmin = kafkaAdmin;
		this.jdbc = jdbc;
		this.json = json;
		this.context = context;
	}

	@BeforeEach
	void startInterview() {
		jdbc.update("DELETE FROM project");   // cascades to files, chunks and sessions
		jdbc.update("DELETE FROM outbox_event");
		jdbc.update("DELETE FROM processed_event");
		llm.reset();
		embeddingModel.reset();
		retries.reset();
		// The fan-out group starts at the newest event (task 2.6): it must be in before anything is sent.
		KafkaListeners.awaitAssigned(context, InterviewEventsFanOut.LISTENER_ID, 6);
		String project = fixture.readyProject().toString();
		GraphQlTester.Response started = graphQlTester.document("""
				mutation ($input: StartInterviewInput!) {
				  startInterview(input: $input) { session { id } firstQuestion { id } }
				}
				""").variable("input", Map.of("projectId", project, "questionCount", 3)).execute();
		sessionId = started.path("startInterview.session.id").entity(String.class).get();
		firstQuestionId = started.path("startInterview.firstQuestion.id").entity(String.class).get();
	}

	@AfterEach
	void unsubscribe() {
		if (subscription != null) {
			subscription.dispose();
		}
	}

	/** Acceptance criterion: a subscription receives ANSWER_EVALUATED and then QUESTION_READY. */
	@Test
	void aSubscriberGetsTheGradeThenTheNextQuestion() {
		List<GraphQlTester.Response> events = subscribe();
		llm.enqueue(LlmPurpose.EVALUATION, strong());

		GraphQlTester.Response submitted = submit(firstQuestionId, "Both read the same refundedAmount.");

		// The request only saved the answer and its event: nothing graded yet, no next question.
		submitted.path("submitAnswer.errors").entityList(Object.class).hasSize(0);
		submitted.path("submitAnswer.answer.text").entity(String.class).isEqualTo("Both read the same refundedAmount.");
		submitted.path("submitAnswer.answer.evaluation").valueIsNull();
		submitted.path("submitAnswer.nextQuestion").valueIsNull();
		submitted.path("submitAnswer.session.status").entity(String.class).isEqualTo("IN_PROGRESS");
		assertThat(llm.calls(LlmPurpose.EVALUATION)).isZero();

		pumpUntil(() -> events.size() >= 2);

		events.get(0).path("interviewEvents.type").entity(String.class).isEqualTo("ANSWER_EVALUATED");
		events.get(0).path("interviewEvents.answer.evaluation.score").entity(Integer.class).isEqualTo(9);
		events.get(0).path("interviewEvents.nextQuestion").valueIsNull();
		events.get(1).path("interviewEvents.type").entity(String.class).isEqualTo("QUESTION_READY");
		events.get(1).path("interviewEvents.nextQuestion.order").entity(Integer.class).isEqualTo(2);
		events.get(1).path("interviewEvents.nextQuestion.isFollowUp").entity(Boolean.class).isEqualTo(false);
		// Nested fields are resolved per event like in a query, the batched snippet included.
		events.get(1).path("interviewEvents.nextQuestion.codeRefs[0].snippet").entity(String.class)
				.satisfies(snippet -> assertThat(snippet).isNotBlank());
		String next = events.get(1).path("interviewEvents.nextQuestion.id").entity(String.class).get();
		events.get(1).path("interviewEvents.session.currentQuestion.id").entity(String.class).isEqualTo(next);
		assertThat(events).hasSize(2);
	}

	/** Acceptance criterion: Phase 1 clients that poll interviewSession still work, and so does sending again. */
	@Test
	void aClientThatOnlyPollsSeesTheGradeThenTheNextQuestion() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());
		submit(firstQuestionId, "Both read the same refundedAmount.");

		pumpUntil(() -> currentQuestionOrder() == 2);

		GraphQlTester.Response session = graphQlTester.document(SESSION).variable("id", sessionId).execute();
		session.path("interviewSession.status").entity(String.class).isEqualTo("IN_PROGRESS");
		session.path("interviewSession.questions[0].answer.evaluation.score").entity(Integer.class).isEqualTo(9);
		session.path("interviewSession.questions[1].answer").valueIsNull();
		pumpUntil(this::questionReadySent);
		// Sending the same answer again returns the stored result, and starts nothing new.
		GraphQlTester.Response again = submit(firstQuestionId, "Both read the same refundedAmount.");
		again.path("submitAnswer.answer.evaluation.score").entity(Integer.class).isEqualTo(9);
		again.path("submitAnswer.nextQuestion.order").entity(Integer.class).isEqualTo(2);
		assertThat(eventTypes()).containsExactly(KafkaAnswerPipeline.ANSWER_SUBMITTED,
				KafkaAnswerPipeline.ANSWER_EVALUATED, KafkaAnswerPipeline.QUESTION_READY);
	}

	/** At-least-once: both worker events of a finished step delivered again (same ids) change nothing. */
	@Test
	void deliveringTheEventsAgainGradesOnceAndAsksOnce() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());
		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> currentQuestionOrder() == 2 && questionReadySent());
		long questionCalls = llm.calls(LlmPurpose.QUESTION);

		jdbc.update("UPDATE outbox_event SET published_at = NULL WHERE aggregate_id = ?::uuid AND type IN (?, ?)",
				sessionId, KafkaAnswerPipeline.ANSWER_SUBMITTED, KafkaAnswerPipeline.ANSWER_EVALUATED);
		assertThat(OutboxPump.publishPending(context)).hasSize(2);
		KafkaConsumerLag.awaitCaughtUp(kafkaAdmin, Map.of(AnswerEvaluationConsumer.NAME, Topics.ANSWER_SUBMITTED,
				InterviewOrchestrator.NAME, Topics.ANSWER_EVALUATED));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(1);
		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(questionCalls);
		assertThat(eventTypes()).hasSize(3);   // no worker wrote its event again
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM question WHERE session_id = ?::uuid AND order_index IS NOT NULL", Integer.class,
				sessionId)).isEqualTo(2);
	}

	/** The model stays down: 4 deliveries (task 2.4), the dead-letter topic, then the session FAILED with why. */
	@Test
	void anUnavailableModelIsRetriedThenTheSessionFails() {
		List<GraphQlTester.Response> events = subscribe();
		IntStream.range(0, 4).forEach(i -> llm.enqueueFailure(LlmPurpose.EVALUATION,
				new LlmUnavailableException(new RuntimeException("model down"))));

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> types(events).contains("SESSION_FAILED"));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(4);
		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId)).containsExactly(1, 2, 3, 4);
		assertThat(retries.deadLettered(Topics.ANSWER_SUBMITTED, sessionId)).isEqualTo(1);
		assertThat(types(events)).containsExactly("SESSION_FAILED");
		events.getFirst().path("interviewEvents.session.status").entity(String.class).isEqualTo("FAILED");
		events.getFirst().path("interviewEvents.session.failureReason").entity(String.class)
				.isEqualTo(MODEL_UNAVAILABLE);
		// The answer is kept, ungraded.
		GraphQlTester.Response session = graphQlTester.document(SESSION).variable("id", sessionId).execute();
		session.path("interviewSession.questions[0].answer.text").entity(String.class)
				.isEqualTo("Both read the same refundedAmount.");
		session.path("interviewSession.questions[0].answer.evaluation").valueIsNull();
	}

	/** A reply the model would get wrong again is not retried: the session fails at once, after one call. */
	@Test
	void anUnusableGradeFailsTheSessionAtOnce() {
		List<GraphQlTester.Response> events = subscribe();
		LlmOutputException unusable = new LlmOutputException(new RuntimeException("no JSON"));
		llm.enqueueFailure(LlmPurpose.EVALUATION, unusable);

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> types(events).contains("SESSION_FAILED"));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(1);
		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId)).isEmpty();
		events.getFirst().path("interviewEvents.session.failureReason").entity(String.class)
				.isEqualTo(unusable.getMessage());
	}

	/** The last answer: SESSION_COMPLETED, sent after the report is written. */
	@Test
	void theLastAnswerCompletesTheSessionWithItsReport() {
		List<GraphQlTester.Response> events = subscribe();
		String question = firstQuestionId;
		for (int order = 1; order <= 3; order++) {
			llm.enqueue(LlmPurpose.EVALUATION, strong());
			submit(question, "Answer " + order);
			int next = order + 1;
			if (order < 3) {
				pumpUntil(() -> currentQuestionOrder() == next);
				question = graphQlTester.document(SESSION).variable("id", sessionId).execute()
						.path("interviewSession.currentQuestion.id").entity(String.class).get();
			}
		}
		pumpUntil(() -> types(events).contains("SESSION_COMPLETED"));

		assertThat(types(events)).containsExactly("ANSWER_EVALUATED", "QUESTION_READY", "ANSWER_EVALUATED",
				"QUESTION_READY", "ANSWER_EVALUATED", "SESSION_COMPLETED");
		GraphQlTester.Response completed = events.getLast();
		completed.path("interviewEvents.session.status").entity(String.class).isEqualTo("COMPLETED");
		completed.path("interviewEvents.session.report.overallScore").entity(Double.class)
				.satisfies(score -> assertThat(score).isPositive());
		completed.path("interviewEvents.session.currentQuestion").valueIsNull();
	}

	/** The transport real clients use (DECISIONS.md D-020): a POST to /graphql that accepts text/event-stream. */
	@Test
	void subscriptionsWorkOverServerSentEvents() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
		String body = json.writeValueAsString(Map.of(
				"query", "subscription ($id: ID!) { interviewEvents(sessionId: $id) { type } }",
				"variables", Map.of("id", sessionId)));
		MvcResult stream = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM).content(body)).andExpect(request().asyncStarted()).andReturn();
		llm.enqueue(LlmPurpose.EVALUATION, strong());

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> stream.getResponse().getContentAsString().contains("QUESTION_READY"));

		String content = stream.getResponse().getContentAsString();
		assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(content).contains("event:next").contains("\"type\":\"ANSWER_EVALUATED\"");
		assertThat(content.indexOf("ANSWER_EVALUATED")).isLessThan(content.indexOf("QUESTION_READY"));
	}

	@Test
	void theKafkaProfileReplacesTheInProcessAnswerPipeline() {
		assertThat(context.getBeanNamesForType(InProcessAnswerPipeline.class)).isEmpty();
		assertThat(context.getBean(AnswerPipeline.class)).isInstanceOf(KafkaAnswerPipeline.class);
	}

	private List<GraphQlTester.Response> subscribe() {
		List<GraphQlTester.Response> received = new CopyOnWriteArrayList<>();
		subscription = graphQlTester.document(SUBSCRIBE).variable("id", sessionId).executeSubscription().toFlux()
				.subscribe(received::add);
		return received;
	}

	private GraphQlTester.Response submit(String questionId, String text) {
		return graphQlTester.document(SUBMIT).variable("input", Map.of("questionId", questionId, "text", text))
				.execute();
	}

	/** Pumps the outbox until {@code done}: the relay's timer is slowed in Kafka tests. */
	private void pumpUntil(Callable<Boolean> done) {
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			OutboxPump.publishPending(context);
			return done.call();
		});
	}

	private int currentQuestionOrder() {
		Integer order = jdbc.queryForObject("""
				SELECT q.order_index FROM interview_session s LEFT JOIN question q ON q.id = s.current_question_id
				WHERE s.id = ?::uuid
				""", Integer.class, sessionId);
		return order == null ? 0 : order;
	}

	/**
	 * The orchestrator stores the next question first and writes QuestionReady in its last transaction (LLD §13.5),
	 * so question 2 can be visible a moment before its event exists. Tests that count events wait for this.
	 */
	private boolean questionReadySent() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM outbox_event
				WHERE aggregate_id = ?::uuid AND type = ? AND published_at IS NOT NULL
				""", Integer.class, sessionId, KafkaAnswerPipeline.QUESTION_READY) == 1;
	}

	private List<String> eventTypes() {
		return jdbc.queryForList("SELECT type FROM outbox_event WHERE aggregate_id = ?::uuid ORDER BY created_at, id",
				String.class, sessionId);
	}

	private static List<String> types(List<GraphQlTester.Response> events) {
		return events.stream().map(event -> event.path("interviewEvents.type").entity(String.class).get()).toList();
	}

	private static EvaluationOutput strong() {
		return new EvaluationOutput(9, List.of("Lost update"), List.of(), List.of(), "Clear.", false, "");
	}

}
