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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.12: {@code Mutation.endInterview} and {@code InterviewSession.report} through the GraphQL engine. */
@IntegrationTest
class EndInterviewGraphQlTest {

	private static final String END = """
			mutation ($input: EndInterviewInput!) {
			  endInterview(input: $input) {
			    session {
			      status
			      report {
			        overallScore summary strengths weaknesses
			        categoryScores { category averageScore questionCount }
			        topicsToReview { topic why }
			      }
			    }
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

	@Autowired
	EndInterviewGraphQlTest(GraphQlTester graphQlTester, FixtureIndexer fixture, FakeLlmGateway llm,
			FakeEmbeddingModel embeddingModel, JdbcTemplate jdbc) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void startAndAnswerOnce() {
		jdbc.update("DELETE FROM project");
		llm.reset();
		embeddingModel.reset();
		String project = fixture.readyProject().toString();
		llm.enqueue(LlmPurpose.QUESTION, new QuestionOutput("Why a RefundRequest body?",
				List.of(new CodeRefOutput("src/main/java/com/example/shop/order/OrderController.java", 27, 30)),
				new RubricOutput(List.of("A point"), List.of(), List.of()), List.of("An outline")));
		GraphQlTester.Response started = graphQlTester.document("""
				mutation ($input: StartInterviewInput!) { startInterview(input: $input) { session { id } firstQuestion { id } } }
				""").variable("input", Map.of("projectId", project)).execute();
		sessionId = started.path("startInterview.session.id").entity(String.class).get();
		String questionId = started.path("startInterview.firstQuestion.id").entity(String.class).get();
		llm.enqueue(LlmPurpose.EVALUATION, new EvaluationOutput(8, List.of(), List.of(), List.of(), "Good.", false, ""));
		graphQlTester.document("""
				mutation ($input: SubmitAnswerInput!) { submitAnswer(input: $input) { errors { code } } }
				""").variable("input", Map.of("questionId", questionId, "text", "It is validated first.")).execute()
				.path("submitAnswer.errors").entityList(Object.class).hasSize(0);
	}

	@Test
	void endingReturnsTheAbandonedSessionWithItsReport() {
		GraphQlTester.Response response = end(sessionId);

		response.path("endInterview.errors").entityList(Object.class).hasSize(0);
		response.path("endInterview.session.status").entity(String.class).isEqualTo("ABANDONED");
		response.path("endInterview.session.report.overallScore").entity(Double.class).isEqualTo(8.0);
		response.path("endInterview.session.report.categoryScores[0].category").entity(String.class)
				.isEqualTo("ARCHITECTURE");
		response.path("endInterview.session.report.categoryScores[0].averageScore").entity(Double.class).isEqualTo(8.0);
		response.path("endInterview.session.report.categoryScores[0].questionCount").entity(Integer.class).isEqualTo(1);
		response.path("endInterview.session.report.topicsToReview[0].topic").entity(String.class)
				.isEqualTo("Optimistic locking in the refund flow");
	}

	@Test
	void anUnknownSessionIsAnErrorOnItsField() {
		GraphQlTester.Response response = end(UUID.randomUUID().toString());

		response.path("endInterview.session").valueIsNull();
		response.path("endInterview.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND");
		response.path("endInterview.errors[0].field").entityList(String.class).containsExactly("input", "sessionId");
	}

	private GraphQlTester.Response end(String id) {
		return graphQlTester.document(END).variable("input", Map.of("sessionId", id)).execute();
	}

}
