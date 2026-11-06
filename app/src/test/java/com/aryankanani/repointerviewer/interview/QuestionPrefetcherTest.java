package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.16 acceptance tests for {@link QuestionPrefetcher}, on the analyzed fixture with {@link FakeLlmGateway}.
 * Prefetching is off in the test profile, so these tests call {@link QuestionPrefetcher#prepare} themselves, on the
 * test thread: no waiting, and each test states exactly when the prefetch runs. {@link QuestionPrefetchBackgroundTest}
 * checks that it really starts in the background when it is on.
 */
@IntegrationTest
class QuestionPrefetcherTest {

	static final String ORDER = "src/main/java/com/example/shop/order/";

	static final String PREFETCHED = "What stops a refund larger than what was paid?";

	private final InterviewService interviewService;

	private final QuestionPrefetcher prefetcher;

	private final InterviewTopicRepository topics;

	private final QuestionRepository questions;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private InterviewSession session;

	private Question first;

	private InterviewTopic secondTopic;

	QuestionPrefetcherTest(@Autowired InterviewService interviewService, @Autowired QuestionPrefetcher prefetcher,
			@Autowired InterviewTopicRepository topics, @Autowired QuestionRepository questions,
			@Autowired FixtureIndexer fixture, @Autowired FakeLlmGateway llm,
			@Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.interviewService = interviewService;
		this.prefetcher = prefetcher;
		this.topics = topics;
		this.questions = questions;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	/** The canned plan (OrderController, OrderService#refund, Order) and question 1 on OrderController#refund. */
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
		secondTopic = topics.findBySessionIdOrderByOrderIndex(session.getId()).get(1);
		llm.reset();   // count only the calls made after the start
	}

	/** Acceptance criterion: the next topic's question is saved as PREPARED and stays hidden. */
	@Test
	void thePrefetchSavesTheNextTopicsQuestionHidden() {
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());

		prefetcher.prepare(session.getId());

		Question prepared = questions.findByTopicIdAndFollowUpFalse(secondTopic.getId()).orElseThrow();
		assertThat(prepared.getText()).isEqualTo(PREFETCHED);
		assertThat(prepared.getStatus()).isEqualTo(QuestionStatus.PREPARED);
		assertThat(prepared.getOrderIndex()).isNull();
		assertThat(interviewService.askedQuestions(session.getId())).extracting(Question::getId)
				.containsExactly(first.getId());
		assertThat(topicStatuses()).containsExactly(TopicStatus.ASKED, TopicStatus.PENDING, TopicStatus.PENDING);
		// Same call as without prefetch: on this session's budget, told which questions were already asked.
		assertThat(llm.requests()).singleElement().satisfies(request -> {
			assertThat(request.sessionId()).isEqualTo(session.getId());
			assertThat(request.variables()).containsEntry("previousQuestions", List.of(first.getText()));
		});
	}

	/** Acceptance criterion: the answer waits only for its grade; the next question is already there. */
	@Test
	void submitAnswerAsksThePrefetchedQuestionWithoutAModelCall() {
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		prefetcher.prepare(session.getId());
		Question prepared = questions.findByTopicIdAndFollowUpFalse(secondTopic.getId()).orElseThrow();
		llm.reset();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));

		SubmitAnswerResult result = submit(first, "It is validated before the service is called.");

		assertThat(result.error()).isNull();
		assertThat(llm.calls(LlmPurpose.QUESTION)).isZero();
		assertThat(result.nextQuestion().getId()).isEqualTo(prepared.getId());
		assertThat(result.nextQuestion().getStatus()).isEqualTo(QuestionStatus.ASKED);
		assertThat(result.nextQuestion().getOrderIndex()).isEqualTo(2);
		assertThat(result.session().getCurrentQuestionId()).isEqualTo(prepared.getId());
		assertThat(topicStatuses()).containsExactly(TopicStatus.DONE, TopicStatus.ASKED, TopicStatus.PENDING);
	}

	@Test
	void aFollowUpInBetweenKeepsThePrefetchedQuestionForTheNextTopic() {
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		prefetcher.prepare(session.getId());
		llm.reset();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(5, true));
		llm.enqueue(LlmPurpose.FOLLOW_UP, question("What if the body is missing?", ORDER + "OrderController.java", 27, 30));
		Question followUp = submit(first, "It reads the body.").nextQuestion();
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));

		SubmitAnswerResult result = submit(followUp, "Validation fails with 400.");

		assertThat(followUp.isFollowUp()).isTrue();
		assertThat(result.nextQuestion().getText()).isEqualTo(PREFETCHED);
		assertThat(result.nextQuestion().getOrderIndex()).isEqualTo(3);
		assertThat(llm.calls(LlmPurpose.QUESTION)).isZero();
	}

	/** Acceptance criterion: a failed prefetch is only logged; submitAnswer writes the question as before. */
	@Test
	void aFailedPrefetchSavesNothingAndSubmitAnswerWritesTheQuestion() {
		llm.enqueueFailure(LlmPurpose.QUESTION, new LlmUnavailableException(new RuntimeException("model down")));

		prefetcher.prepare(session.getId());   // does not throw

		assertThat(questions.findByTopicIdAndFollowUpFalse(secondTopic.getId())).isEmpty();
		assertThat(topicStatuses()).containsExactly(TopicStatus.ASKED, TopicStatus.PENDING, TopicStatus.PENDING);

		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		SubmitAnswerResult result = submit(first, "It is validated first.");

		assertThat(result.error()).isNull();
		assertThat(result.nextQuestion().getText()).isEqualTo(PREFETCHED);
		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(2);   // the failed prefetch, then submitAnswer's own
	}

	@Test
	void noGroundedQuestionLeavesTheTopicPendingForSubmitAnswer() {
		QuestionOutput invented = question("What does Ghost do?", "src/Ghost.java", 1, 5);
		llm.enqueue(LlmPurpose.QUESTION, invented);
		llm.enqueue(LlmPurpose.QUESTION, invented);   // the retry with feedback

		prefetcher.prepare(session.getId());

		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(2);
		assertThat(questions.findByTopicIdAndFollowUpFalse(secondTopic.getId())).isEmpty();
		assertThat(topicStatuses()).containsExactly(TopicStatus.ASKED, TopicStatus.PENDING, TopicStatus.PENDING);
	}

	/**
	 * Acceptance criterion: the race. The prefetch saves its question while submitAnswer's own QUESTION call is
	 * still running. Both lock the topic row, so submitAnswer sees the saved one, asks it and drops its own: no
	 * error, and still one main question for the topic.
	 */
	@Test
	void aQuestionPrefetchedWhileSubmitAnswerWritesItsOwnWins() {
		llm.enqueue(LlmPurpose.EVALUATION, evaluation(9, false));
		llm.enqueue(LlmPurpose.QUESTION, question("submitAnswer's own question", ORDER + "OrderService.java", 27, 42));
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());   // taken by the prefetch, inside the call above
		llm.duringNextCall(LlmPurpose.QUESTION, () -> prefetcher.prepare(session.getId()));

		SubmitAnswerResult result = submit(first, "It is validated first.");

		assertThat(result.error()).isNull();
		assertThat(result.nextQuestion().getText()).isEqualTo(PREFETCHED);
		assertThat(result.nextQuestion().getOrderIndex()).isEqualTo(2);
		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM question WHERE topic_id = ? AND NOT is_follow_up",
				Integer.class, secondTopic.getId())).isEqualTo(1);
	}

	@Test
	void aTopicThatAlreadyHasItsQuestionIsNotPrefetchedAgain() {
		llm.enqueue(LlmPurpose.QUESTION, secondTopicQuestion());
		prefetcher.prepare(session.getId());

		prefetcher.prepare(session.getId());

		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(1);
	}

	@Test
	void anEndedInterviewIsNotPrefetched() {
		interviewService.endInterview(new EndInterviewCommand(session.getId()));

		prefetcher.prepare(session.getId());

		assertThat(llm.requests()).isEmpty();
		assertThat(questions.findByTopicIdAndFollowUpFalse(secondTopic.getId())).isEmpty();
	}

	private SubmitAnswerResult submit(Question question, String text) {
		return interviewService.submitAnswer(new SubmitAnswerCommand(question.getId(), text));
	}

	private List<TopicStatus> topicStatuses() {
		return topics.findBySessionIdOrderByOrderIndex(session.getId()).stream().map(InterviewTopic::getStatus).toList();
	}

	static QuestionOutput secondTopicQuestion() {
		return question(PREFETCHED, ORDER + "OrderService.java", 27, 42);
	}

	static QuestionOutput question(String text, String path, int start, int end) {
		return new QuestionOutput(text, List.of(new CodeRefOutput(path, start, end)),
				new RubricOutput(List.of("Validation happens first"), List.of("Bean Validation"), List.of()),
				List.of("The body is validated", "Then the service runs"));
	}

	private static EvaluationOutput evaluation(int score, boolean followUpNeeded) {
		return new EvaluationOutput(score, List.of("validation"), List.of("error handling"), List.of(),
				"Good start.", followUpNeeded, followUpNeeded ? "Check what happens on bad input." : "");
	}

}
