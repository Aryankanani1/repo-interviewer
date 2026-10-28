package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.11: {@code Mutation.submitAnswer} and the answer and follow-up links, through the GraphQL engine. */
@IntegrationTest
class SubmitAnswerGraphQlTest {

	private static final String CONTROLLER = "src/main/java/com/example/shop/order/OrderController.java";

	private static final String SUBMIT = """
			mutation ($input: SubmitAnswerInput!) {
			  submitAnswer(input: $input) {
			    answer { text evaluation { score verdict feedback missedPoints idealAnswerOutline } }
			    nextQuestion { id order isFollowUp parent { id } }
			    session { status }
			    errors { code field }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private String sessionId;

	private String firstQuestionId;

	SubmitAnswerGraphQlTest(@Autowired GraphQlTester graphQlTester, @Autowired FixtureIndexer fixture,
			@Autowired FakeLlmGateway llm, @Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.graphQlTester = graphQlTester;
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
		String project = fixture.readyProject().toString();
		llm.enqueue(LlmPurpose.QUESTION, question("Why does refund take a RefundRequest body?"));
		GraphQlTester.Response started = graphQlTester.document("""
				mutation ($input: StartInterviewInput!) {
				  startInterview(input: $input) { session { id } firstQuestion { id } }
				}
				""").variable("input", Map.of("projectId", project)).execute();
		sessionId = started.path("startInterview.session.id").entity(String.class).get();
		firstQuestionId = started.path("startInterview.firstQuestion.id").entity(String.class).get();
	}

	@Test
	void theAnswerComesBackGradedWithTheIdealOutlineAndTheFollowUp() {
		llm.enqueue(LlmPurpose.EVALUATION, new EvaluationOutput(5, List.of("validation"), List.of("error handling"),
				List.of(), "Good start.", true, "Check bad input."));
		llm.enqueue(LlmPurpose.FOLLOW_UP, question("What if the body is missing?"));

		GraphQlTester.Response response = submit(firstQuestionId, "It is validated first.");

		response.path("submitAnswer.errors").entityList(Object.class).hasSize(0);
		response.path("submitAnswer.answer.text").entity(String.class).isEqualTo("It is validated first.");
		response.path("submitAnswer.answer.evaluation.score").entity(Integer.class).isEqualTo(5);
		response.path("submitAnswer.answer.evaluation.verdict").entity(String.class).isEqualTo("ADEQUATE");
		response.path("submitAnswer.answer.evaluation.missedPoints").entityList(String.class)
				.containsExactly("error handling");
		response.path("submitAnswer.answer.evaluation.idealAnswerOutline").entityList(String.class)
				.containsExactly("The body is validated");
		response.path("submitAnswer.nextQuestion.order").entity(Integer.class).isEqualTo(2);
		response.path("submitAnswer.nextQuestion.isFollowUp").entity(Boolean.class).isEqualTo(true);
		response.path("submitAnswer.nextQuestion.parent.id").entity(String.class).isEqualTo(firstQuestionId);
		response.path("submitAnswer.session.status").entity(String.class).isEqualTo("IN_PROGRESS");
	}

	@Test
	void theSessionShowsEachQuestionWithItsAnswer() {
		llm.enqueue(LlmPurpose.EVALUATION, new EvaluationOutput(9, List.of(), List.of(), List.of(), "Great.", false, ""));
		llm.enqueue(LlmPurpose.QUESTION, new QuestionOutput("What stops a refund larger than what was paid?",
				List.of(new CodeRefOutput("src/main/java/com/example/shop/order/OrderService.java", 27, 42)),
				new RubricOutput(List.of("refundable"), List.of(), List.of()), List.of("Compares with refundable")));
		submit(firstQuestionId, "It is validated first.");

		graphQlTester.document("""
				query ($id: ID!) {
				  interviewSession(id: $id) { questions { order answer { evaluation { score } } } }
				}
				""").variable("id", sessionId).execute()
				.path("interviewSession.questions").entityList(Object.class).hasSize(2)
				.path("interviewSession.questions[0].answer.evaluation.score").entity(Integer.class).isEqualTo(9)
				.path("interviewSession.questions[1].answer").valueIsNull();
	}

	@Test
	void anEmptyAnswerIsAnErrorOnTheTextField() {
		GraphQlTester.Response response = submit(firstQuestionId, "  ");

		response.path("submitAnswer.answer").valueIsNull();
		response.path("submitAnswer.errors[0].code").entity(String.class).isEqualTo("ANSWER_EMPTY");
		response.path("submitAnswer.errors[0].field").entityList(String.class).containsExactly("input", "text");
	}

	private GraphQlTester.Response submit(String questionId, String text) {
		return graphQlTester.document(SUBMIT).variable("input", Map.of("questionId", questionId, "text", text))
				.execute();
	}

	private static QuestionOutput question(String text) {
		return new QuestionOutput(text, List.of(new CodeRefOutput(CONTROLLER, 27, 30)),
				new RubricOutput(List.of("Validation happens first"), List.of(), List.of()),
				List.of("The body is validated"));
	}

}
