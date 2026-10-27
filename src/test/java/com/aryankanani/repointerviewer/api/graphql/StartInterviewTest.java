package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.10: {@code Mutation.startInterview} and {@code Query.interviewSession} through the GraphQL engine. */
@IntegrationTest
class StartInterviewTest {

	private static final String CONTROLLER = "src/main/java/com/example/shop/order/OrderController.java";

	private static final String START = """
			mutation ($input: StartInterviewInput!) {
			  startInterview(input: $input) {
			    session { id status difficulty plannedQuestionCount failureReason }
			    firstQuestion { id order category text isFollowUp codeRefs { path startLine endLine } }
			    errors { code message field }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private UUID project;

	StartInterviewTest(@Autowired GraphQlTester graphQlTester, @Autowired FixtureIndexer fixture,
			@Autowired FakeLlmGateway llm, @Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void readyProject() {
		jdbc.update("DELETE FROM project");
		llm.reset();
		embeddingModel.reset();
		project = fixture.readyProject();
		llm.enqueue(LlmPurpose.QUESTION, new QuestionOutput("Why does refund take a RefundRequest body?",
				List.of(new CodeRefOutput(CONTROLLER, 27, 30)),
				new RubricOutput(List.of("SECRET RUBRIC POINT"), List.of(), List.of()), List.of("SECRET OUTLINE")));
	}

	@Test
	void startReturnsTheSessionAndTheFirstQuestion() {
		GraphQlTester.Response response = start(Map.of("projectId", project, "difficulty", "MID"));

		response.path("startInterview.errors").entityList(Object.class).hasSize(0);
		response.path("startInterview.session.status").entity(String.class).isEqualTo("IN_PROGRESS");
		response.path("startInterview.session.difficulty").entity(String.class).isEqualTo("MID");
		response.path("startInterview.session.plannedQuestionCount").entity(Integer.class).isEqualTo(3);
		response.path("startInterview.firstQuestion.order").entity(Integer.class).isEqualTo(1);
		response.path("startInterview.firstQuestion.isFollowUp").entity(Boolean.class).isEqualTo(false);
		response.path("startInterview.firstQuestion.codeRefs[0].path").entity(String.class).isEqualTo(CONTROLLER);
		response.path("startInterview.firstQuestion.codeRefs[0].startLine").entity(Integer.class).isEqualTo(27);
	}

	@Test
	void theSessionCanBeReadBackWithItsProjectAndQuestions() {
		String sessionId = start(Map.of("projectId", project)).path("startInterview.session.id").entity(String.class)
				.get();

		graphQlTester.document("""
				query ($id: ID!) {
				  interviewSession(id: $id) {
				    status
				    project { name status }
				    questions { order text }
				    currentQuestion { order }
				  }
				}
				""").variable("id", sessionId).execute()
				.path("interviewSession.status").entity(String.class).isEqualTo("IN_PROGRESS")
				.path("interviewSession.project.status").entity(String.class).isEqualTo("READY")
				.path("interviewSession.questions").entityList(Object.class).hasSize(1)
				.path("interviewSession.questions[0].text").entity(String.class)
				.isEqualTo("Why does refund take a RefundRequest body?")
				.path("interviewSession.currentQuestion.order").entity(Integer.class).isEqualTo(1);

		graphQlTester.document("query ($id: ID!) { project(id: $id) { sessions { id status } } }")
				.variable("id", project).execute()
				.path("project.sessions[0].id").entity(String.class).isEqualTo(sessionId);
	}

	/** The rubric would give the answer away: the schema has no field for it, and the response never carries it. */
	@Test
	void theRubricIsNeverExposed() {
		String body = start(Map.of("projectId", project)).path("startInterview").entity(Object.class).get().toString();
		assertThat(body).doesNotContain("SECRET RUBRIC POINT", "SECRET OUTLINE");

		graphQlTester.document("""
				query ($id: ID!) { interviewSession(id: $id) { currentQuestion { rubric } } }
				""").variable("id", UUID.randomUUID()).execute()
				.errors().satisfy(errors -> assertThat(errors).anyMatch(error -> error.getMessage().contains("rubric")));
	}

	@Test
	void anInvalidQuestionCountIsAnErrorOnItsField() {
		GraphQlTester.Response response = start(Map.of("projectId", project, "questionCount", 9));

		response.path("startInterview.session").valueIsNull();
		response.path("startInterview.errors[0].code").entity(String.class).isEqualTo("INVALID_QUESTION_COUNT");
		response.path("startInterview.errors[0].field").entityList(String.class).containsExactly("input", "questionCount");
	}

	@Test
	void aFailedStartStillReturnsTheFailedSession() {
		llm.reset();   // drop the valid question: the canned one cites OrderService, not the first topic's code
		for (int i = 0; i < 6; i++) {
			llm.enqueue(LlmPurpose.QUESTION, new QuestionOutput("Why?",
					List.of(new CodeRefOutput("src/Nowhere.java", 1, 2)), null, List.of()));
		}

		GraphQlTester.Response response = start(Map.of("projectId", project));

		response.path("startInterview.session.status").entity(String.class).isEqualTo("FAILED");
		response.path("startInterview.session.failureReason").entity(String.class).satisfies(
				reason -> assertThat(reason).startsWith("Could not write a question grounded"));
		response.path("startInterview.firstQuestion").valueIsNull();
		response.path("startInterview.errors[0].code").entity(String.class).isEqualTo("LLM_UNAVAILABLE");
	}

	private GraphQlTester.Response start(Map<String, Object> input) {
		return graphQlTester.document(START).variable("input", input).execute();
	}

}
