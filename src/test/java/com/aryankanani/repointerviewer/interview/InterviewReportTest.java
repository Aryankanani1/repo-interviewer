package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import com.aryankanani.repointerviewer.llm.PromptMarkup;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.12 acceptance tests: the report after a completed interview and after an early {@code endInterview}, on the
 * analyzed fixture with {@link FakeLlmGateway}. The canned plan's topics are ARCHITECTURE (OrderController),
 * FAILURE_HANDLING (OrderService#refund) and DATA_MODEL (Order).
 */
@IntegrationTest
class InterviewReportTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private final InterviewService interviewService;

	private final QuestionRepository questions;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private InterviewSession session;

	private Question first;

	InterviewReportTest(@Autowired InterviewService interviewService, @Autowired QuestionRepository questions,
			@Autowired FixtureIndexer fixture, @Autowired FakeLlmGateway llm,
			@Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.interviewService = interviewService;
		this.questions = questions;
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
		llm.enqueue(LlmPurpose.QUESTION, question("Why a RefundRequest body?", "OrderController.java", 27, 30));
		StartResult started = interviewService.start(new StartInterviewCommand(project, null, null, null));
		session = started.session();
		first = started.firstQuestion();
		llm.reset();
	}

	/** Acceptance criterion: the report exists after completion, with the numbers computed by code. */
	@Test
	void aCompletedInterviewGetsItsReportWithScoresFromCode() {
		SubmitAnswerResult last = answerAllThree(9, 6, 3);

		assertThat(last.error()).isNull();
		InterviewSession completed = last.session();
		assertThat(completed.getStatus()).isEqualTo(SessionStatus.COMPLETED);
		InterviewReport report = completed.getReport();
		assertThat(report.overallScore()).isEqualTo(6.0);   // (9 + 6 + 3) / 3
		assertThat(completed.getOverallScore()).isEqualByComparingTo(new BigDecimal("6.00"));
		assertThat(report.categoryScores()).containsExactly(
				new CategoryScore(QuestionCategory.ARCHITECTURE, 9.0, 1),
				new CategoryScore(QuestionCategory.DATA_MODEL, 3.0, 1),
				new CategoryScore(QuestionCategory.FAILURE_HANDLING, 6.0, 1));
		assertThat(report.summary()).isNotBlank();                            // the model's text
		assertThat(report.topicsToReview()).extracting(ReviewTopic::topic).isNotEmpty();
		assertThat(jdbc.queryForObject("SELECT overall_score FROM interview_session WHERE id = ?", BigDecimal.class,
				session.getId())).isEqualByComparingTo("6.00");
	}

	@Test
	void theReportPromptGetsTheScoresAsFactsAndTheTranscript() {
		answerAllThree(9, 6, 3);

		LlmRequest<?> report = llm.requests().stream().filter(request -> request.purpose() == LlmPurpose.REPORT)
				.findFirst().orElseThrow();
		assertThat(report.sessionId()).isEqualTo(session.getId());
		assertThat(report.variables()).containsEntry("overallScore", "6.0").containsEntry("difficulty", "JUNIOR");
		assertThat(report.variables().get("categoryScores")).isEqualTo(List.of(
				"ARCHITECTURE: 9.0 over 1 question", "DATA_MODEL: 3.0 over 1 question",
				"FAILURE_HANDLING: 6.0 over 1 question"));
		assertThat(((PromptMarkup) report.variables().get("transcript")).text())
				.startsWith("Question 1 (ARCHITECTURE): Why a RefundRequest body?\n<candidate_answer>\nAnswer one.\n"
						+ "</candidate_answer>\nGrade: 9/10, STRONG.")
				.contains("Question 3 (DATA_MODEL)");
	}

	/** Acceptance criterion: the report exists after an early endInterview, from the answers given. */
	@Test
	void endingEarlyAbandonsTheInterviewAndReportsOnTheAnswersGiven() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(7));
		llm.enqueue(LlmPurpose.QUESTION, question("What stops a too large refund?", "OrderService.java", 27, 42));
		Question second = submit(first, "Answer one.").nextQuestion();

		EndResult ended = interviewService.endInterview(new EndInterviewCommand(session.getId()));

		assertThat(ended.error()).isNull();
		assertThat(ended.session().getStatus()).isEqualTo(SessionStatus.ABANDONED);
		assertThat(ended.session().getCurrentQuestionId()).isNull();
		assertThat(ended.session().getCompletedAt()).isNotNull();
		assertThat(ended.session().getReport().overallScore()).isEqualTo(7.0);
		assertThat(ended.session().getReport().categoryScores())
				.containsExactly(new CategoryScore(QuestionCategory.ARCHITECTURE, 7.0, 1));
		assertThat(questions.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(QuestionStatus.SKIPPED);
		assertThat(questions.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(QuestionStatus.ANSWERED);
	}

	@Test
	void endingWithNoGradedAnswerGivesNoReportAndNoModelCall() {
		EndResult ended = interviewService.endInterview(new EndInterviewCommand(session.getId()));

		assertThat(ended.session().getStatus()).isEqualTo(SessionStatus.ABANDONED);
		assertThat(ended.session().getReport()).isNull();
		assertThat(llm.requests()).isEmpty();
		assertThat(questions.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(QuestionStatus.SKIPPED);
	}

	@Test
	void endingTwiceWritesTheReportOnce() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(7));
		llm.enqueue(LlmPurpose.QUESTION, question("What stops a too large refund?", "OrderService.java", 27, 42));
		submit(first, "Answer one.");
		interviewService.endInterview(new EndInterviewCommand(session.getId()));

		EndResult again = interviewService.endInterview(new EndInterviewCommand(session.getId()));

		assertThat(again.session().getStatus()).isEqualTo(SessionStatus.ABANDONED);
		assertThat(again.session().getReport()).isNotNull();
		assertThat(llm.calls(LlmPurpose.REPORT)).isEqualTo(1);
	}

	/** Ending never waits for the model: the session is COMPLETED even when the report fails, and a resend retries it. */
	@Test
	void aFailedReportLeavesTheSessionEndedAndResendingTheLastAnswerRetriesIt() {
		llm.enqueueFailure(LlmPurpose.REPORT, new LlmUnavailableException(new IllegalStateException("down")));

		SubmitAnswerResult last = answerAllThree(9, 6, 3);

		assertThat(last.session().getStatus()).isEqualTo(SessionStatus.COMPLETED);
		assertThat(last.session().getReport()).isNull();
		assertThat(last.error().code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE);

		SubmitAnswerResult resent = submit(last.question(), "Answer three.");

		assertThat(resent.error()).isNull();
		assertThat(resent.session().getReport().overallScore()).isEqualTo(6.0);
		assertThat(llm.calls(LlmPurpose.REPORT)).isEqualTo(2);
		assertThat(llm.calls(LlmPurpose.EVALUATION)).isEqualTo(3);   // nothing graded twice
	}

	@Test
	void onlyARunningOrEndedInterviewCanBeEnded() {
		jdbc.update("UPDATE interview_session SET status = 'FAILED' WHERE id = ?", session.getId());

		assertThatThrownBy(() -> interviewService.endInterview(new EndInterviewCommand(session.getId())))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.SESSION_NOT_ACTIVE);
					assertThat(ex.field()).contains("sessionId");
				});
		assertThatThrownBy(() -> interviewService.endInterview(new EndInterviewCommand(UUID.randomUUID())))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	/** Answers the three main questions with these scores, no follow-ups; returns the last result. */
	private SubmitAnswerResult answerAllThree(int scoreOne, int scoreTwo, int scoreThree) {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(scoreOne));
		llm.enqueue(LlmPurpose.QUESTION, question("What stops a too large refund?", "OrderService.java", 27, 42));
		Question second = submit(first, "Answer one.").nextQuestion();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(scoreTwo));
		llm.enqueue(LlmPurpose.QUESTION, question("What sets REFUNDED?", "Order.java", 48, 53));
		Question third = submit(second, "Answer two.").nextQuestion();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(scoreThree));
		return submit(third, "Answer three.");
	}

	private SubmitAnswerResult submit(Question question, String text) {
		return interviewService.submitAnswer(new SubmitAnswerCommand(question.getId(), text));
	}

	private static QuestionOutput question(String text, String file, int start, int end) {
		return new QuestionOutput(text, List.of(new CodeRefOutput(ORDER + file, start, end)),
				new RubricOutput(List.of("A point"), List.of(), List.of()), List.of("An outline"));
	}

	private static EvaluationOutput evaluation(int score) {
		return new EvaluationOutput(score, List.of(), List.of(), List.of(), "Feedback.", false, "");
	}

}
