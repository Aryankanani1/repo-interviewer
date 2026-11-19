package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.KafkaListeners;
import com.aryankanani.repointerviewer.api.events.InterviewEventsFanOut;
import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerFlowEvent;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import reactor.core.Disposable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 2.5 acceptance tests, GraphQL side (task 4.1): the answer-flow events interview-service publishes reach the
 * {@code interviewEvents} subscription, every field read through {@code InterviewApi} ({@link FakeInterviewApi} here)
 * and the snippet through {@code AnalysisApi}. The events are sent to a real broker as interview-service's outbox
 * relay sends them (an {@link EventEnvelope} as JSON, keyed by session id; the gateway has no outbox since task 4.1
 * step 4a); that interview-service sends them at the right moments is its own {@code AsyncAnswerTest}.
 */
@KafkaIntegrationTest
class InterviewEventsSubscriptionTest {

	private static final String SUBSCRIBE = """
			subscription ($id: ID!) {
			  interviewEvents(sessionId: $id) {
			    type
			    answer { text evaluation { score verdict } }
			    nextQuestion { id order isFollowUp codeRefs { path snippet } }
			    session { status failureReason currentQuestion { id } }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	private final KafkaOperations<String, String> kafka;

	private final JsonMapper json;

	private final ApplicationContext context;

	private SessionDto session;

	private QuestionDto first;

	private QuestionDto second;

	private AnswerDto answer;

	private Disposable subscription;

	InterviewEventsSubscriptionTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeInterviewApi interviews, @Autowired KafkaOperations<String, String> kafka,
			@Autowired JsonMapper json, @Autowired ApplicationContext context) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.interviews = interviews;
		this.kafka = kafka;
		this.json = json;
		this.context = context;
	}

	/** The state after the first answer was graded and the second question asked. */
	@BeforeEach
	void gradedFirstAnswerAndSecondQuestion() {
		fixture.reset();
		interviews.reset();
		// The fan-out group starts at the newest event (task 2.6): it must be in before anything is sent.
		KafkaListeners.awaitAssigned(context, InterviewEventsFanOut.LISTENER_ID, 6);
		SessionDto started = FakeInterviewApi.session(fixture.readyProject());
		first = interviews.add(FakeInterviewApi.question(started.id(), 1, null));
		second = interviews.add(FakeInterviewApi.question(started.id(), 2, null));
		answer = interviews.add(FakeInterviewApi.graded(first.id(), "Both read the same refundedAmount.", 9));
		session = interviews.add(FakeInterviewApi.asking(started, second.id()));
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

		send(Topics.ANSWER_EVALUATED, AnswerFlowEvent.ANSWER_EVALUATED, new AnswerFlowEvent(answer.id(), first.id(),
				null));
		send(Topics.QUESTION_READY, AnswerFlowEvent.QUESTION_READY, new AnswerFlowEvent(answer.id(), first.id(),
				second.id()));
		awaitUntil(() -> events.size() >= 2);

		events.get(0).path("interviewEvents.type").entity(String.class).isEqualTo("ANSWER_EVALUATED");
		events.get(0).path("interviewEvents.answer.evaluation.score").entity(Integer.class).isEqualTo(9);
		events.get(0).path("interviewEvents.nextQuestion").valueIsNull();
		events.get(1).path("interviewEvents.type").entity(String.class).isEqualTo("QUESTION_READY");
		events.get(1).path("interviewEvents.nextQuestion.order").entity(Integer.class).isEqualTo(2);
		events.get(1).path("interviewEvents.nextQuestion.isFollowUp").entity(Boolean.class).isEqualTo(false);
		// Nested fields are resolved per event like in a query, the batched snippet included.
		events.get(1).path("interviewEvents.nextQuestion.codeRefs[0].snippet").entity(String.class)
				.satisfies(snippet -> assertThat(snippet).isNotBlank());
		events.get(1).path("interviewEvents.session.currentQuestion.id").entity(String.class)
				.isEqualTo(second.id().toString());
		assertThat(events).hasSize(2);
	}

	@Test
	void aFailedSessionIsSentWithItsReasonAndTheUngradedAnswer() {
		interviews.add(FakeInterviewApi.ungraded(first.id(), "Both read the same refundedAmount."));
		interviews.add(new SessionDto(session.id(), session.userId(), session.projectId(), SessionStatus.FAILED,
				session.difficulty(),
				session.focusAreas(), session.plannedQuestionCount(), "The AI model is not available right now.",
				null, session.startedAt(), null, null));
		List<GraphQlTester.Response> events = subscribe();

		send(Topics.QUESTION_READY, AnswerFlowEvent.SESSION_FAILED, new AnswerFlowEvent(answer.id(), first.id(),
				null));
		awaitUntil(() -> !events.isEmpty());

		GraphQlTester.Response failed = events.getFirst();
		failed.path("interviewEvents.type").entity(String.class).isEqualTo("SESSION_FAILED");
		failed.path("interviewEvents.session.status").entity(String.class).isEqualTo("FAILED");
		failed.path("interviewEvents.session.failureReason").entity(String.class)
				.isEqualTo("The AI model is not available right now.");
		failed.path("interviewEvents.answer.text").entity(String.class).isEqualTo("Both read the same refundedAmount.");
		failed.path("interviewEvents.answer.evaluation").valueIsNull();
	}

	/** The transport real clients use (DECISIONS.md D-020): a POST to /graphql that accepts text/event-stream. */
	@Test
	void subscriptionsWorkOverServerSentEvents() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
		String body = json.writeValueAsString(Map.of(
				"query", "subscription ($id: ID!) { interviewEvents(sessionId: $id) { type } }",
				"variables", Map.of("id", session.id())));
		MvcResult stream = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM).content(body)).andExpect(request().asyncStarted()).andReturn();

		send(Topics.ANSWER_EVALUATED, AnswerFlowEvent.ANSWER_EVALUATED, new AnswerFlowEvent(answer.id(), first.id(),
				null));
		send(Topics.QUESTION_READY, AnswerFlowEvent.QUESTION_READY, new AnswerFlowEvent(answer.id(), first.id(),
				second.id()));
		awaitUntil(() -> stream.getResponse().getContentAsString().contains("QUESTION_READY"));

		String content = stream.getResponse().getContentAsString();
		assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(content).contains("event:next").contains("\"type\":\"ANSWER_EVALUATED\"");
		assertThat(content.indexOf("ANSWER_EVALUATED")).isLessThan(content.indexOf("QUESTION_READY"));
	}

	private List<GraphQlTester.Response> subscribe() {
		List<GraphQlTester.Response> received = new CopyOnWriteArrayList<>();
		subscription = graphQlTester.document(SUBSCRIBE).variable("id", session.id()).executeSubscription().toFlux()
				.subscribe(received::add);
		return received;
	}

	/** An event of this session, as interview-service's outbox relay sends it, and waits for Kafka's ack. */
	private void send(String topic, String type, AnswerFlowEvent payload) {
		EventEnvelope event = new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), session.id(),
				json.valueToTree(payload));
		try {
			kafka.send(topic, session.id().toString(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException | ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("Kafka did not take the event", ex);
		}
	}

	private static void awaitUntil(Callable<Boolean> done) {
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(done);
	}

}
