package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.11 acceptance tests for {@link InterviewService#submitAnswer}, on the analyzed fixture with
 * {@link FakeLlmGateway}. Every test starts an interview with the canned plan (topics: OrderController,
 * OrderService#refund, Order) and question 1 on OrderController#refund. Grades and next questions are queued per
 * test, so each test states exactly what the "model" answers.
 */
@IntegrationTest
class SubmitAnswerTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private final InterviewService interviewService;

	private final InterviewTopicRepository topics;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private InterviewSession session;

	private Question first;

	SubmitAnswerTest(@Autowired InterviewService interviewService, @Autowired InterviewTopicRepository topics,
			@Autowired FixtureIndexer fixture, @Autowired FakeLlmGateway llm,
			@Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.interviewService = interviewService;
		this.topics = topics;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void startInterview() {
		jdbc.update("DELETE FROM project");
		llm.reset();
		embeddingModel.reset();
		UUID project = fixture.readyProject();
		llm.enqueue(LlmPurpose.QUESTION, question("Why does OrderController#refund take a RefundRequest body?",
				ORDER + "OrderController.java", 27, 30));
		StartResult started = interviewService.start(new StartInterviewCommand(project, null, null, null));
		session = started.session();
		first = started.firstQuestion();
		llm.reset();   // count only the calls made by submitAnswer
	}

	@Test
	void theVerdictComesFromCodeAndTheNextTopicIsAsked() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(14, false));   // clamped to 10 by normalization
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());

		SubmitAnswerResult result = submit(first, "It is validated before the service is called.");

		assertThat(result.error()).isNull();
		assertThat(result.answer().getScore()).isEqualTo(10);
		assertThat(result.answer().getVerdict()).isEqualTo(AnswerVerdict.STRONG);
		assertThat(result.question().getStatus()).isEqualTo(QuestionStatus.ANSWERED);
		assertThat(result.nextQuestion().getOrderIndex()).isEqualTo(2);
		assertThat(result.nextQuestion().isFollowUp()).isFalse();
		assertThat(result.session().getCurrentQuestionId()).isEqualTo(result.nextQuestion().getId());
		assertThat(topicStatuses()).containsExactly(TopicStatus.DONE, TopicStatus.ASKED, TopicStatus.PENDING);
	}

	@Test
	void theGradingPromptGetsTheQuestionsCodeTheRubricAndTheAnswer() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(6, false));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());

		submit(first, "It is validated first.");

		LlmRequest<?> grading = llm.requests().getFirst();
		assertThat(grading.purpose()).isEqualTo(LlmPurpose.EVALUATION);
		assertThat(grading.sessionId()).isEqualTo(session.getId());
		assertThat((String) grading.variables().get("codeChunks")).startsWith("<code_chunk path=\"" + ORDER
				+ "OrderController.java\" lines=\"27-30\" symbol=\"OrderController#refund\">");
		assertThat((String) grading.variables().get("rubric")).startsWith("Must mention:\n- Validation happens first");
		assertThat(grading.variables()).containsEntry("answer", "It is validated first.");
	}

	/** Acceptance criterion: at most one follow-up per topic. */
	@Test
	void aTopicGetsOneFollowUpAndThenMovesOn() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(5, true));
		llm.enqueue(LlmPurpose.FOLLOW_UP, question("What if the body is missing?", ORDER + "OrderController.java", 27, 30));

		SubmitAnswerResult afterFirst = submit(first, "It reads the body.");

		Question followUp = afterFirst.nextQuestion();
		assertThat(followUp.isFollowUp()).isTrue();
		assertThat(followUp.getParentQuestionId()).isEqualTo(first.getId());
		assertThat(followUp.getOrderIndex()).isEqualTo(2);
		assertThat(followUp.getTopicId()).isEqualTo(first.getTopicId());
		assertThat(topics.findById(first.getTopicId()).orElseThrow().getFollowUpCount()).isEqualTo(1);

		llm.enqueue(LlmPurpose.EVALUATION, evaluation(5, true));     // the model asks for another one: refused
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());

		SubmitAnswerResult afterFollowUp = submit(followUp, "Validation fails with 400.");

		assertThat(afterFollowUp.nextQuestion().isFollowUp()).isFalse();
		assertThat(afterFollowUp.nextQuestion().getOrderIndex()).isEqualTo(3);
		assertThat(llm.calls(LlmPurpose.FOLLOW_UP)).isEqualTo(1);
		assertThat(topicStatuses()).containsExactly(TopicStatus.DONE, TopicStatus.ASKED, TopicStatus.PENDING);
	}

	/** Acceptance criterion: a score outside 3..7 never triggers a follow-up. */
	@ParameterizedTest(name = "score {0}")
	@ValueSource(ints = { 2, 8 })
	void aScoreOutsideThreeToSevenNeverTriggersAFollowUp(int score) {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(score, true));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());

		SubmitAnswerResult result = submit(first, "An answer.");

		assertThat(result.nextQuestion().isFollowUp()).isFalse();
		assertThat(llm.calls(LlmPurpose.FOLLOW_UP)).isZero();
	}

	@Test
	void noFollowUpWhenTheRestOfTheInterviewNeedsTheBudget() {
		jdbc.update("UPDATE interview_session SET llm_call_count = 21 WHERE id = ?", session.getId());
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(5, true));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());

		SubmitAnswerResult result = submit(first, "An answer.");

		assertThat(result.nextQuestion().isFollowUp()).isFalse();   // 9 calls left, 10 still needed
	}

	/** Acceptance criterion: the session completes after the last topic. */
	@Test
	void theSessionCompletesAfterTheLastTopic() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		Question second = submit(first, "Answer one.").nextQuestion();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));
		llm.enqueue(LlmPurpose.QUESTION, question("What changes the status to REFUNDED?", ORDER + "Order.java", 48, 53));
		Question third = submit(second, "Answer two.").nextQuestion();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));

		SubmitAnswerResult last = submit(third, "Answer three.");

		assertThat(last.nextQuestion()).isNull();
		assertThat(last.session().getStatus()).isEqualTo(SessionStatus.COMPLETED);
		assertThat(last.session().getCurrentQuestionId()).isNull();
		assertThat(last.session().getCompletedAt()).isNotNull();
		assertThat(topicStatuses()).containsOnly(TopicStatus.DONE);

		// Re-sending the last answer of a finished interview returns its result, not SESSION_NOT_ACTIVE.
		assertThat(submit(third, "Answer three.").answer().getId()).isEqualTo(last.answer().getId());
	}

	/** Acceptance criterion: the same answer again gives the same result with no extra model call. */
	@Test
	void resendingTheSameAnswerReturnsTheSameResultWithoutCallingTheModel() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(6, false));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		SubmitAnswerResult once = submit(first, "It is validated first.");
		int calls = llm.requests().size();

		SubmitAnswerResult again = submit(first, "  It is validated first.\n");   // same text after trimming

		assertThat(llm.requests()).hasSize(calls);
		assertThat(again.answer().getId()).isEqualTo(once.answer().getId());
		assertThat(again.answer().getScore()).isEqualTo(6);
		assertThat(again.nextQuestion().getId()).isEqualTo(once.nextQuestion().getId());
		assertThat(again.error()).isNull();
	}

	/** Acceptance criterion. */
	@Test
	void aDifferentTextForAnAnsweredQuestionIsAlreadyAnswered() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(6, false));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		submit(first, "It is validated first.");

		assertError(() -> submit(first, "Actually, something else."), ErrorCode.ALREADY_ANSWERED, "questionId");
	}

	@Test
	void onlyTheCurrentQuestionCanBeAnswered() {
		UUID prepared = jdbc.queryForObject("""
				INSERT INTO question (session_id, topic_id, category, text, rubric, status)
				SELECT session_id, id, category, 'Prepared, not asked', '{}'::jsonb, 'PREPARED'
				FROM interview_topic WHERE session_id = ? AND order_index = 2
				RETURNING id
				""", UUID.class, session.getId());

		assertError(() -> interviewService.submitAnswer(new SubmitAnswerCommand(prepared, "Hi")),
				ErrorCode.NOT_CURRENT_QUESTION, "questionId");
		assertThat(answerCount()).isZero();
	}

	@Test
	void emptyAndTooLongAnswersAreRejectedBeforeAnythingIsSaved() {
		assertError(() -> submit(first, "   "), ErrorCode.ANSWER_EMPTY, "text");
		assertError(() -> submit(first, "x".repeat(4001)), ErrorCode.ANSWER_TOO_LONG, "text");
		assertThat(answerCount()).isZero();
		assertThat(llm.requests()).isEmpty();
	}

	@Test
	void anInterviewThatEndedTakesNoNewAnswers() {
		jdbc.update("UPDATE interview_session SET status = 'ABANDONED' WHERE id = ?", session.getId());

		assertThatThrownBy(() -> submit(first, "Too late."))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_NOT_ACTIVE));
	}

	@Test
	void aFailedGradingKeepsTheAnswerAndSendingItAgainGradesIt() {
		llm.enqueueFailure(LlmPurpose.EVALUATION, new LlmUnavailableException(new IllegalStateException("down")));

		SubmitAnswerResult failed = submit(first, "It is validated first.");

		assertThat(failed.error().code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
		assertThat(failed.answer().getEvaluatedAt()).isNull();
		assertThat(failed.nextQuestion()).isNull();
		assertThat(failed.session().getCurrentQuestionId()).isEqualTo(first.getId());

		llm.enqueue(LlmPurpose.EVALUATION, evaluation(7, false));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		SubmitAnswerResult retried = submit(first, "It is validated first.");

		assertThat(retried.error()).isNull();
		assertThat(retried.answer().getId()).isEqualTo(failed.answer().getId());
		assertThat(retried.answer().getScore()).isEqualTo(7);
		assertThat(retried.nextQuestion().getOrderIndex()).isEqualTo(2);
	}

	@Test
	void aFailedNextQuestionIsRetriedWithoutGradingAgain() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(7, false));
		llm.enqueueFailure(LlmPurpose.QUESTION, new LlmUnavailableException(new IllegalStateException("down")));

		SubmitAnswerResult failed = submit(first, "It is validated first.");

		assertThat(failed.error().code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
		assertThat(failed.answer().getScore()).isEqualTo(7);   // the grade is kept
		assertThat(failed.nextQuestion()).isNull();

		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		SubmitAnswerResult retried = submit(first, "It is validated first.");

		assertThat(retried.nextQuestion().getOrderIndex()).isEqualTo(2);
		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(1);   // not graded twice
	}

	private SubmitAnswerResult submit(Question question, String text) {
		return interviewService.submitAnswer(new SubmitAnswerCommand(question.getId(), text));
	}

	private List<TopicStatus> topicStatuses() {
		return topics.findBySessionIdOrderByOrderIndex(session.getId()).stream().map(InterviewTopic::getStatus).toList();
	}

	private int answerCount() {
		return jdbc.queryForObject("SELECT count(*) FROM answer", Integer.class);
	}

	private static void assertError(ThrowingCallable call, ErrorCode code,
			String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.field()).contains(field);
		});
	}

	private static QuestionOutput secondTopicQuestion() {
		return question("What stops a refund larger than what was paid?", ORDER + "OrderService.java", 27, 42);
	}

	private static QuestionOutput question(String text, String path, int start, int end) {
		return new QuestionOutput(text, List.of(new CodeRefOutput(path, start, end)),
				new RubricOutput(List.of("Validation happens first"), List.of("Bean Validation"), List.of()),
				List.of("The body is validated", "Then the service runs"));
	}

	private static EvaluationOutput evaluation(int score, boolean followUpNeeded) {
		return new EvaluationOutput(score, List.of("validation"), List.of("error handling"), List.of(),
				"Good start.", followUpNeeded, followUpNeeded ? "Check what happens on bad input." : "");
	}

}
