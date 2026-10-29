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
import com.aryankanani.repointerviewer.llm.output.PlanOutput;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.10 acceptance tests for {@link InterviewService#start}, on the analyzed fixture with
 * {@link FakeLlmGateway}. Its canned plan has three grounded topics: OrderController, OrderService#refund, Order.
 */
@IntegrationTest
class InterviewServiceTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private final InterviewService interviewService;

	private final InterviewTopicRepository topics;

	private final QuestionRepository questions;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private UUID project;

	InterviewServiceTest(@Autowired InterviewService interviewService, @Autowired InterviewTopicRepository topics,
			@Autowired QuestionRepository questions, @Autowired FixtureIndexer fixture, @Autowired FakeLlmGateway llm,
			@Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.interviewService = interviewService;
		this.topics = topics;
		this.questions = questions;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void readyProject() {
		jdbc.update("DELETE FROM project");   // cascades to sessions, topics and questions
		llm.reset();
		embeddingModel.reset();
		project = fixture.readyProject();
	}

	/** Acceptance criterion: question 1 comes back with valid refs. */
	@Test
	void happyPathReturnsTheFirstQuestionWithValidRefs() {
		llm.enqueue(LlmPurpose.QUESTION, question("Why does OrderController#refund take a RefundRequest body?",
				new CodeRefOutput(ORDER + "OrderController.java", 27, 30),
				new CodeRefOutput(ORDER + "PaymentController.java", 1, 9)));   // invented: dropped

		StartResult result = interviewService.start(command(null));

		assertThat(result.error()).isNull();
		InterviewSession session = result.session();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
		assertThat(session.getPlannedQuestionCount()).isEqualTo(3);   // 5 requested, 3 grounded topics planned
		Question first = result.firstQuestion();
		assertThat(session.getCurrentQuestionId()).isEqualTo(first.getId());
		assertThat(first.getOrderIndex()).isEqualTo(1);
		assertThat(first.getStatus()).isEqualTo(QuestionStatus.ASKED);
		assertThat(first.getAskedAt()).isNotNull();
		assertThat(first.getCodeRefs()).containsExactly(new CodeRef(ORDER + "OrderController.java", 27, 30));
		assertThat(first.getCategory()).isEqualTo(QuestionCategory.ARCHITECTURE);
		assertThat(first.getPromptVersion()).isEqualTo("question-generation@fake0000");

		Question stored = questions.findById(first.getId()).orElseThrow();   // read back from JSONB
		assertThat(stored.getRubric().mustMention()).containsExactly("Validation happens before the service");
		assertThat(stored.getCodeRefs()).isEqualTo(first.getCodeRefs());
		assertThat(topics.findBySessionIdOrderByOrderIndex(session.getId())).extracting(InterviewTopic::getStatus)
				.containsExactly(TopicStatus.ASKED, TopicStatus.PENDING, TopicStatus.PENDING);
	}

	@Test
	void theModelIsCalledForThePlanThenTheQuestionWithTheRetrievedCode() {
		llm.enqueue(LlmPurpose.QUESTION, question("Why?", new CodeRefOutput(ORDER + "OrderController.java", 27, 30)));

		StartResult result = interviewService.start(command(null));

		assertThat(llm.requests()).extracting(LlmRequest::purpose).containsExactly(LlmPurpose.PLAN, LlmPurpose.QUESTION);
		assertThat(llm.requests()).allSatisfy(request -> {
			assertThat(request.projectId()).isEqualTo(project);
			assertThat(request.sessionId()).isEqualTo(result.session().getId());   // counts against its budget
		});
		LlmRequest<?> plan = llm.requests().get(0);
		assertThat(plan.variables()).containsEntry("difficulty", "JUNIOR").containsEntry("questionCount", 5);
		assertThat((String) plan.variables().get("summary")).startsWith("One-liner: A small order service");
		LlmRequest<?> question = llm.requests().get(1);
		assertThat(((PromptMarkup) question.variables().get("codeChunks")).text()).contains("<code_chunk path=\"" + ORDER
				+ "OrderController.java\" lines=\"27-30\" symbol=\"OrderController#refund\">");
		assertThat(question.variables().get("retryFeedback")).isNull();
	}

	/** Acceptance criterion: a plan with invented paths drops those topics. */
	@Test
	void topicsAboutInventedCodeAreDroppedFromThePlan() {
		llm.enqueue(LlmPurpose.PLAN, new PlanOutput(List.of(
				topic("Overall design", ORDER + "OrderController.java", "OrderController"),
				topic("Payments", "src/main/java/com/example/shop/payment/PaymentService.java", "PaymentService"),
				topic("Refund rules", ORDER + "OrderService.java", "OrderService#refund"),
				topic("Order entity", ORDER + "Order.java", "Order"))));
		llm.enqueue(LlmPurpose.QUESTION, question("Why?", new CodeRefOutput(ORDER + "OrderController.java", 27, 30)));

		StartResult result = interviewService.start(command(null));

		assertThat(result.session().getPlannedQuestionCount()).isEqualTo(3);
		assertThat(topics.findBySessionIdOrderByOrderIndex(result.session().getId()))
				.extracting(InterviewTopic::getTitle).containsExactly("Overall design", "Refund rules", "Order entity");
	}

	@Test
	void tooFewGroundedTopicsFailTheSession() {
		llm.enqueue(LlmPurpose.PLAN, new PlanOutput(List.of(
				topic("Overall design", ORDER + "OrderController.java", "OrderController"),
				topic("Payments", "src/main/java/com/example/shop/payment/PaymentService.java", "PaymentService"),
				topic("Shipping", "src/main/java/com/example/shop/shipping/Courier.java", "Courier"))));

		StartResult result = interviewService.start(command(null));

		assertThat(result.error().code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
		assertThat(result.session().getStatus()).isEqualTo(SessionStatus.FAILED);
		assertThat(result.session().getFailureReason()).startsWith("Could not plan questions grounded");
		assertThat(topics.findBySessionIdOrderByOrderIndex(result.session().getId())).isEmpty();
		assertThat(llm.calls(LlmPurpose.QUESTION)).isZero();
	}

	/** Acceptance criterion: invalid refs get one retry, then the topic is skipped. */
	@Test
	void invalidRefsAreRetriedOnceThenTheTopicIsSkipped() {
		QuestionOutput invented = question("Why?", new CodeRefOutput(ORDER + "PaymentController.java", 1, 9));
		llm.enqueue(LlmPurpose.QUESTION, invented);
		llm.enqueue(LlmPurpose.QUESTION, invented);
		llm.enqueue(LlmPurpose.QUESTION, question("What stops a refund larger than what was paid?",
				new CodeRefOutput(ORDER + "OrderService.java", 27, 42)));

		StartResult result = interviewService.start(command(null));

		assertThat(result.error()).isNull();
		assertThat(result.firstQuestion().getText()).startsWith("What stops a refund");
		assertThat(result.firstQuestion().getOrderIndex()).isEqualTo(1);
		assertThat(llm.calls(LlmPurpose.QUESTION)).isEqualTo(3);
		assertThat(topics.findBySessionIdOrderByOrderIndex(result.session().getId())).extracting(InterviewTopic::getStatus)
				.containsExactly(TopicStatus.SKIPPED, TopicStatus.ASKED, TopicStatus.PENDING);
		@SuppressWarnings("unchecked")
		List<String> feedback = (List<String>) llm.requests().get(2).variables().get("retryFeedback");   // 2nd QUESTION
		assertThat(feedback).first().asString().startsWith("No codeRef was inside");
		assertThat(feedback).contains(ORDER + "OrderController.java lines 27-30");
	}

	@Test
	void whenNoTopicGetsAGroundedQuestionTheSessionFails() {
		for (int i = 0; i < 6; i++) {
			llm.enqueue(LlmPurpose.QUESTION, question("Why?", new CodeRefOutput(ORDER + "Nowhere.java", 1, 2)));
		}

		StartResult result = interviewService.start(command(null));

		assertThat(result.error().code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE);
		assertThat(result.session().getStatus()).isEqualTo(SessionStatus.FAILED);
		assertThat(result.session().getFailureReason()).isEqualTo(InterviewService.NO_GROUNDED_QUESTION);
		assertThat(topics.findBySessionIdOrderByOrderIndex(result.session().getId())).extracting(InterviewTopic::getStatus)
				.containsOnly(TopicStatus.SKIPPED);
	}

	@Test
	void anUnavailableModelFailsTheSessionWithItsMessage() {
		llm.enqueueFailure(LlmPurpose.PLAN, new LlmUnavailableException(new IllegalStateException("down")));

		StartResult result = interviewService.start(command(null));

		assertThat(result.error()).isInstanceOf(LlmUnavailableException.class);
		assertThat(result.session().getStatus()).isEqualTo(SessionStatus.FAILED);
		assertThat(result.session().getFailureReason()).isEqualTo(result.error().getMessage());
	}

	/** Acceptance criterion. */
	@Test
	void aProjectThatIsNotReadyCannotBeInterviewedAbout() {
		UUID parsing = fixture.newProject();   // status PARSING

		assertThatThrownBy(() -> interviewService.start(new StartInterviewCommand(parsing, null, null, null)))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.PROJECT_NOT_READY);
					assertThat(ex.field()).contains("projectId");
				});
		assertThat(sessionCount()).isZero();
		assertThat(llm.requests()).isEmpty();
	}

	/** Acceptance criterion. */
	@ParameterizedTest
	@ValueSource(ints = { 2, 9 })
	void aQuestionCountOutsideThreeToEightIsRejected(int count) {
		assertThatThrownBy(() -> interviewService.start(command(count)))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_QUESTION_COUNT);
					assertThat(ex.field()).contains("questionCount");
				});
		assertThat(sessionCount()).isZero();
		assertThat(llm.requests()).isEmpty();
	}

	@Test
	void anUnknownProjectIsNotFound() {
		assertThatThrownBy(() -> interviewService.start(new StartInterviewCommand(UUID.randomUUID(), null, null, null)))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void focusAreasAreStoredOnceEachAndSentToThePlan() {
		llm.enqueue(LlmPurpose.QUESTION, question("Why?", new CodeRefOutput(ORDER + "OrderController.java", 27, 30)));

		StartResult result = interviewService.start(new StartInterviewCommand(project, Difficulty.MID,
				List.of(QuestionCategory.CONCURRENCY, QuestionCategory.TESTING, QuestionCategory.CONCURRENCY), 3));

		assertThat(result.session().getFocusAreas()).containsExactly(QuestionCategory.CONCURRENCY, QuestionCategory.TESTING);
		assertThat(result.session().getDifficulty()).isEqualTo(Difficulty.MID);
		assertThat(llm.requests().getFirst().variables()).containsEntry("focusAreas", List.of("CONCURRENCY", "TESTING"));
	}

	private StartInterviewCommand command(Integer questionCount) {
		return new StartInterviewCommand(project, null, null, questionCount);
	}

	private int sessionCount() {
		return jdbc.queryForObject("SELECT count(*) FROM interview_session", Integer.class);
	}

	private static PlannedTopic topic(String title, String path, String symbol) {
		return new PlannedTopic(title, QuestionCategory.DESIGN_DECISION, List.of(path), List.of(symbol), "Why.");
	}

	private static QuestionOutput question(String text, CodeRefOutput... refs) {
		return new QuestionOutput(text, List.of(refs),
				new RubricOutput(List.of("Validation happens before the service"), List.of(), List.of()),
				List.of("The body is validated", "The service checks the amount"));
	}

}
