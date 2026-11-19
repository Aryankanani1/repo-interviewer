package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.KafkaConsumerLag;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.RecordingRetryListener;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerFlowEvent;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionStatus;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.contracts.interview.SubmitAnswerResponse;
import com.aryankanani.repointerviewer.llm.LlmOutputException;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.outbox.OutboxPump;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Task 2.5 acceptance tests: answers graded and followed up by Kafka workers. A real broker and the fake model; the
 * outbox is pumped by the test. Since task 4.1 the calls go through {@link InterviewApi} (in this JVM the controller
 * itself), and what the {@code interviewEvents} subscription will get is checked here as the events this service
 * publishes; the subscription itself is the GraphQL app's ({@code InterviewEventsSubscriptionTest} there).
 */
@KafkaIntegrationTest
class AsyncAnswerTest {

	private static final String MODEL_UNAVAILABLE = new LlmUnavailableException(new RuntimeException()).getMessage();

	private static final String ANALYSIS_UNAVAILABLE = "The analysis service is not available right now. "
			+ "Try again in a minute.";

	/** The events a subscriber sees, in order: everything but AnswerSubmitted, which only the workers read. */
	private static final List<String> SUBSCRIBED = List.of(AnswerFlowEvent.ANSWER_EVALUATED,
			AnswerFlowEvent.QUESTION_READY, AnswerFlowEvent.SESSION_COMPLETED, AnswerFlowEvent.SESSION_FAILED);

	private final InterviewApi interviews;

	private final FakeAnalysisApi fixture;

	private final FakeLlmGateway llm;

	private final RecordingRetryListener retries;

	private final KafkaAdmin kafkaAdmin;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	private UUID sessionId;

	private UUID firstQuestionId;

	AsyncAnswerTest(@Autowired InterviewApi interviews, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeLlmGateway llm, @Autowired RecordingRetryListener retries, @Autowired KafkaAdmin kafkaAdmin,
			@Autowired JdbcTemplate jdbc, @Autowired ApplicationContext context) {
		this.interviews = interviews;
		this.fixture = fixture;
		this.llm = llm;
		this.retries = retries;
		this.kafkaAdmin = kafkaAdmin;
		this.jdbc = jdbc;
		this.context = context;
	}

	@BeforeEach
	void startInterview() {
		jdbc.update("DELETE FROM interview_session");   // cascades to topics, questions and answers
		fixture.reset();
		jdbc.update("DELETE FROM outbox_event");
		jdbc.update("DELETE FROM processed_event");
		llm.reset();
		retries.reset();
		StartResponse started = interviews.start(new StartInterviewRequest(TestUsers.CANDIDATE,
				fixture.readyProject(), null, null, 3));
		sessionId = started.session().id();
		firstQuestionId = started.firstQuestion().id();
	}

	/** Acceptance criterion: the request only saves the answer; workers grade it, then ask the next question. */
	@Test
	void theWorkersGradeTheAnswerThenAskTheNextQuestion() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());

		SubmitAnswerResponse submitted = submit(firstQuestionId, "Both read the same refundedAmount.");

		// The request only saved the answer and its event: nothing graded yet, no next question.
		assertThat(submitted.error()).isNull();
		assertThat(submitted.answer().text()).isEqualTo("Both read the same refundedAmount.");
		assertThat(submitted.answer().evaluation()).isNull();
		assertThat(submitted.nextQuestion()).isNull();
		assertThat(submitted.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
		assertThat(llm.calls(LlmPurpose.EVALUATION)).isZero();

		pumpUntil(this::questionReadySent);

		assertThat(subscribedEvents())
				.containsExactly(AnswerFlowEvent.ANSWER_EVALUATED, AnswerFlowEvent.QUESTION_READY);
		assertThat(answer(firstQuestionId).evaluation().score()).isEqualTo(9);
		List<QuestionDto> asked = interviews.askedQuestions(List.of(sessionId));
		assertThat(asked).extracting(QuestionDto::order).containsExactly(1, 2);
		assertThat(asked.get(1).followUp()).isFalse();
		assertThat(session().currentQuestionId()).isEqualTo(asked.get(1).id());
		// The subscription reads everything else through InterviewApi: the event only names the question.
		assertThat(jdbc.queryForObject("""
				SELECT payload ->> 'nextQuestionId' FROM outbox_event WHERE aggregate_id = ? AND type = ?
				""", String.class, sessionId, AnswerFlowEvent.QUESTION_READY)).isEqualTo(asked.get(1).id().toString());
	}

	/** Acceptance criterion: Phase 1 clients that poll the session still work, and so does sending again. */
	@Test
	void aClientThatOnlyPollsSeesTheGradeThenTheNextQuestion() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());
		submit(firstQuestionId, "Both read the same refundedAmount.");

		pumpUntil(() -> currentQuestionOrder() == 2);

		assertThat(session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
		assertThat(answer(firstQuestionId).evaluation().score()).isEqualTo(9);
		UUID second = interviews.askedQuestions(List.of(sessionId)).get(1).id();
		assertThat(interviews.answers(List.of(second))).isEmpty();
		pumpUntil(this::questionReadySent);
		// Sending the same answer again returns the stored result, and starts nothing new.
		SubmitAnswerResponse again = submit(firstQuestionId, "Both read the same refundedAmount.");
		assertThat(again.answer().evaluation().score()).isEqualTo(9);
		assertThat(again.nextQuestion().order()).isEqualTo(2);
		assertThat(eventTypes()).containsExactly(AnswerFlowEvent.ANSWER_SUBMITTED,
				AnswerFlowEvent.ANSWER_EVALUATED, AnswerFlowEvent.QUESTION_READY);
	}

	/** At-least-once: both worker events of a finished step delivered again (same ids) change nothing. */
	@Test
	void deliveringTheEventsAgainGradesOnceAndAsksOnce() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());
		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> currentQuestionOrder() == 2 && questionReadySent());
		long questionCalls = llm.calls(LlmPurpose.QUESTION);

		jdbc.update("UPDATE outbox_event SET published_at = NULL WHERE aggregate_id = ? AND type IN (?, ?)",
				sessionId, AnswerFlowEvent.ANSWER_SUBMITTED, AnswerFlowEvent.ANSWER_EVALUATED);
		assertThat(OutboxPump.publishPending(context)).hasSize(2);
		KafkaConsumerLag.awaitCaughtUp(kafkaAdmin, Map.of(AnswerEvaluationConsumer.NAME, Topics.ANSWER_SUBMITTED,
				InterviewOrchestrator.NAME, Topics.ANSWER_EVALUATED));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(1);
		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(questionCalls);
		assertThat(eventTypes()).hasSize(3);   // no worker wrote its event again
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM question WHERE session_id = ? AND order_index IS NOT NULL", Integer.class,
				sessionId)).isEqualTo(2);
	}

	/** The model stays down: 4 deliveries (task 2.4), the dead-letter topic, then the session FAILED with why. */
	@Test
	void anUnavailableModelIsRetriedThenTheSessionFails() {
		IntStream.range(0, 4).forEach(i -> llm.enqueueFailure(LlmPurpose.EVALUATION,
				new LlmUnavailableException(new RuntimeException("model down"))));

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> sent(AnswerFlowEvent.SESSION_FAILED));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(4);
		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId.toString())).containsExactly(1, 2, 3, 4);
		assertThat(retries.deadLettered(Topics.ANSWER_SUBMITTED, sessionId.toString())).isEqualTo(1);
		assertThat(subscribedEvents()).containsExactly(AnswerFlowEvent.SESSION_FAILED);
		assertThat(session().status()).isEqualTo(SessionStatus.FAILED);
		assertThat(session().failureReason()).isEqualTo(MODEL_UNAVAILABLE);
		// The answer is kept, ungraded.
		assertThat(answer(firstQuestionId).text()).isEqualTo("Both read the same refundedAmount.");
		assertThat(answer(firstQuestionId).evaluation()).isNull();
	}

	/**
	 * Task 3.6 acceptance criterion: the embedding model fails twice while the orchestrator searches the code for the
	 * next question (Ollama unreachable for a moment, as in the 3.4 load test); the step is retried and goes on.
	 * Since task 4.1 the search is analysis-service's: it answers 503 LLM_UNAVAILABLE, and the client rebuilds a
	 * {@code ModelUnavailableException}, which the orchestrator retries like a model of its own.
	 */
	@Test
	void anEmbeddingModelThatComesBackDuringTheRetriesLetsTheInterviewGoOn() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());   // no follow-up: the next topic's question needs a search
		fixture.failNextSearches(2);

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> currentQuestionOrder() == 2);

		assertThat(retries.failedAttempts(Topics.ANSWER_EVALUATED, sessionId.toString())).containsExactly(1, 2);
		assertThat(retries.deadLettered(Topics.ANSWER_EVALUATED, sessionId.toString())).isZero();
		assertThat(session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
	}

	/** Task 3.6 acceptance criterion: the embedding model stays down, the event is dead-lettered, the session FAILS. */
	@Test
	void anEmbeddingModelThatStaysDownFailsTheSessionWithItsOwnReason() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());
		fixture.failNextSearches(4);

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> sent(AnswerFlowEvent.SESSION_FAILED));

		assertThat(retries.failedAttempts(Topics.ANSWER_EVALUATED, sessionId.toString())).containsExactly(1, 2, 3, 4);
		assertThat(retries.deadLettered(Topics.ANSWER_EVALUATED, sessionId.toString())).isEqualTo(1);
		assertThat(session().failureReason())
				.isEqualTo(FakeAnalysisApi.EMBEDDING_UNAVAILABLE);   // analysis-service's message, over the wire
	}

	/**
	 * Task 4.2: analysis-service does not answer once while the answer is graded (its client's guard gives up with
	 * SERVICE_UNAVAILABLE). The worker retries it like a model that is down, and the second delivery grades it.
	 */
	@Test
	void anAnalysisServiceThatComesBackDuringTheRetriesLetsTheGradingGoOn() {
		llm.enqueue(LlmPurpose.EVALUATION, strong());
		fixture.failNextCall(new ServiceUnavailableException(ANALYSIS_UNAVAILABLE));

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> currentQuestionOrder() == 2);

		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId.toString())).containsExactly(1);
		assertThat(retries.deadLettered(Topics.ANSWER_SUBMITTED, sessionId.toString())).isZero();
		assertThat(answer(firstQuestionId).evaluation()).isNotNull();
	}

	/** Task 4.2: analysis-service stays down: 4 deliveries, the dead-letter topic, the session FAILED with why. */
	@Test
	void anAnalysisServiceThatStaysDownFailsTheSessionWithAClearReason() {
		fixture.failEveryCall(new ServiceUnavailableException(ANALYSIS_UNAVAILABLE));

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> sent(AnswerFlowEvent.SESSION_FAILED));

		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId.toString())).containsExactly(1, 2, 3, 4);
		assertThat(retries.deadLettered(Topics.ANSWER_SUBMITTED, sessionId.toString())).isEqualTo(1);
		assertThat(session().status()).isEqualTo(SessionStatus.FAILED);
		assertThat(session().failureReason()).isEqualTo(ANALYSIS_UNAVAILABLE);
		assertThat(llm.calls(LlmPurpose.EVALUATION)).isZero();   // the code to grade against never came
	}

	/**
	 * Task 4.4: the session was deleted with its project before its answer was graded. The worker finds it gone and
	 * skips the event: no model call, no retry, no dead letter, and no SessionFailed for a session nobody can see.
	 */
	@Test
	void anAnswerOfADeletedSessionIsSkipped() {
		submit(firstQuestionId, "Both read the same refundedAmount.");
		UUID project = jdbc.queryForObject("SELECT project_id FROM interview_session WHERE id = ?", UUID.class,
				sessionId);
		interviews.deleteByProject(project);

		assertThat(OutboxPump.publishPending(context)).hasSize(1);   // AnswerSubmitted: outbox rows are not cascaded
		KafkaConsumerLag.awaitCaughtUp(kafkaAdmin, Map.of(AnswerEvaluationConsumer.NAME, Topics.ANSWER_SUBMITTED));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isZero();
		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId.toString())).isEmpty();
		assertThat(retries.deadLettered(Topics.ANSWER_SUBMITTED, sessionId.toString())).isZero();
		assertThat(eventTypes()).containsExactly(AnswerFlowEvent.ANSWER_SUBMITTED);
	}

	/** A reply the model would get wrong again is not retried: the session fails at once, after one call. */
	@Test
	void anUnusableGradeFailsTheSessionAtOnce() {
		LlmOutputException unusable = new LlmOutputException(new RuntimeException("no JSON"));
		llm.enqueueFailure(LlmPurpose.EVALUATION, unusable);

		submit(firstQuestionId, "Both read the same refundedAmount.");
		pumpUntil(() -> sent(AnswerFlowEvent.SESSION_FAILED));

		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(1);
		assertThat(retries.failedAttempts(Topics.ANSWER_SUBMITTED, sessionId.toString())).isEmpty();
		assertThat(session().failureReason()).isEqualTo(unusable.getMessage());
	}

	/** The last answer: SessionCompleted, sent after the report is written. */
	@Test
	void theLastAnswerCompletesTheSessionWithItsReport() {
		UUID question = firstQuestionId;
		for (int order = 1; order <= 3; order++) {
			llm.enqueue(LlmPurpose.EVALUATION, strong());
			submit(question, "Answer " + order);
			int next = order + 1;
			if (order < 3) {
				pumpUntil(() -> currentQuestionOrder() == next);
				question = session().currentQuestionId();
			}
		}
		pumpUntil(() -> sent(AnswerFlowEvent.SESSION_COMPLETED));

		assertThat(subscribedEvents()).containsExactly(AnswerFlowEvent.ANSWER_EVALUATED,
				AnswerFlowEvent.QUESTION_READY, AnswerFlowEvent.ANSWER_EVALUATED, AnswerFlowEvent.QUESTION_READY,
				AnswerFlowEvent.ANSWER_EVALUATED, AnswerFlowEvent.SESSION_COMPLETED);
		SessionDto completed = session();
		assertThat(completed.status()).isEqualTo(SessionStatus.COMPLETED);
		assertThat(completed.report().overallScore()).isPositive();
		assertThat(completed.currentQuestionId()).isNull();
	}

	@Test
	void theKafkaProfileReplacesTheInProcessAnswerPipeline() {
		assertThat(context.getBeanNamesForType(InProcessAnswerPipeline.class)).isEmpty();
		assertThat(context.getBean(AnswerPipeline.class)).isInstanceOf(KafkaAnswerPipeline.class);
	}

	private SubmitAnswerResponse submit(UUID questionId, String text) {
		return interviews.submitAnswer(questionId, new AnswerRequest(text));
	}

	private SessionDto session() {
		return interviews.sessions(List.of(sessionId)).getFirst();
	}

	private AnswerDto answer(UUID questionId) {
		return interviews.answers(List.of(questionId)).getFirst();
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
				WHERE s.id = ?
				""", Integer.class, sessionId);
		return order == null ? 0 : order;
	}

	/**
	 * The orchestrator stores the next question first and writes QuestionReady in its last transaction (LLD §13.5),
	 * so question 2 can be visible a moment before its event exists. Tests that count events wait for this.
	 */
	private boolean questionReadySent() {
		return sent(AnswerFlowEvent.QUESTION_READY);
	}

	/** At least one event of this type was published to Kafka. */
	private boolean sent(String type) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND type = ? AND published_at IS NOT NULL
				""", Integer.class, sessionId, type) > 0;
	}

	private List<String> eventTypes() {
		return jdbc.queryForList("SELECT type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, id",
				String.class, sessionId);
	}

	private List<String> subscribedEvents() {
		return eventTypes().stream().filter(SUBSCRIBED::contains).toList();
	}

	private static EvaluationOutput strong() {
		return new EvaluationOutput(9, List.of("Lost update"), List.of(), List.of(), "Clear.", false, "");
	}

}
