package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionPublisherException;
import org.springframework.graphql.test.tester.GraphQlTester;
import reactor.core.publisher.Flux;

/**
 * Task 4.4 acceptance tests, 403: the caller ({@link TestUsers#CANDIDATE}, see {@code IntegrationTest}) reaches
 * nothing of another user's. A query gets a top-level FORBIDDEN error, a mutation a FORBIDDEN in its payload on the
 * input field, a subscription is refused before it opens; in each case the service behind is not asked to act.
 */
@IntegrationTest
class OwnershipTest {

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi analysis;

	private final FakeInterviewApi interviews;

	private UUID theirProject;

	private SessionDto theirSession;

	private QuestionDto theirQuestion;

	OwnershipTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
		this.interviews = interviews;
	}

	@BeforeEach
	void anotherUsersProjectAndInterview() {
		analysis.reset();
		interviews.reset();
		theirProject = analysis.readyProjectOf(TestUsers.OTHER);
		theirSession = interviews.add(FakeInterviewApi.session(theirProject, TestUsers.OTHER));
		theirQuestion = interviews.add(FakeInterviewApi.question(theirSession.id(), 1, null));
		interviews.add(FakeInterviewApi.asking(theirSession, theirQuestion.id()));
	}

	@Test
	void anotherUsersProjectIsForbidden() {
		graphQlTester.document("query ($id: ID!) { project(id: $id) { id } }").variable("id", theirProject)
				.execute()
				.errors().satisfy(errors -> assertForbidden(errors, "This project belongs to another user."))
				.path("project").valueIsNull();
	}

	@Test
	void theProjectListHoldsOnlyTheCallersProjects() {
		UUID mine = analysis.readyProject();

		graphQlTester.document("{ projects(first: 10) { edges { node { id } } } }").execute()
				.path("projects.edges[*].node.id").entityList(UUID.class).containsExactly(mine);
	}

	/** Acceptance criterion: another user's session is 403. */
	@Test
	void anotherUsersInterviewIsForbidden() {
		graphQlTester.document("query ($id: ID!) { interviewSession(id: $id) { id status } }")
				.variable("id", theirSession.id()).execute()
				.errors().satisfy(errors -> assertForbidden(errors, "This interview belongs to another user."))
				.path("interviewSession").valueIsNull();
	}

	@Test
	void startingAnInterviewOnAnotherUsersProjectIsForbidden() {
		graphQlTester.document("""
				mutation ($input: StartInterviewInput!) {
				  startInterview(input: $input) { session { id } errors { code field } }
				}
				""").variable("input", Map.of("projectId", theirProject)).execute()
				.path("startInterview.session").valueIsNull()
				.path("startInterview.errors[0].code").entity(String.class).isEqualTo("FORBIDDEN")
				.path("startInterview.errors[0].field").entityList(String.class).containsExactly("input", "projectId");
		assertThat(interviews.calls("start")).isZero();
	}

	@Test
	void answeringAnotherUsersQuestionIsForbidden() {
		graphQlTester.document("""
				mutation ($input: SubmitAnswerInput!) {
				  submitAnswer(input: $input) { answer { id } errors { code field } }
				}
				""").variable("input", Map.of("questionId", theirQuestion.id(), "text", "Not mine to answer."))
				.execute()
				.path("submitAnswer.answer").valueIsNull()
				.path("submitAnswer.errors[0].code").entity(String.class).isEqualTo("FORBIDDEN")
				.path("submitAnswer.errors[0].field").entityList(String.class).containsExactly("input", "questionId");
		assertThat(interviews.calls("submitAnswer")).isZero();
	}

	@Test
	void endingAnotherUsersInterviewIsForbidden() {
		graphQlTester.document("""
				mutation ($input: EndInterviewInput!) { endInterview(input: $input) { session { id } errors { code } } }
				""").variable("input", Map.of("sessionId", theirSession.id())).execute()
				.path("endInterview.errors[0].code").entity(String.class).isEqualTo("FORBIDDEN");
		assertThat(interviews.calls("end")).isZero();
	}

	@Test
	void reanalyzingAnotherUsersProjectIsForbidden() {
		graphQlTester.document("""
				mutation ($input: ReanalyzeProjectInput!) { reanalyzeProject(input: $input) { errors { code } } }
				""").variable("input", Map.of("projectId", theirProject)).execute()
				.path("reanalyzeProject.errors[0].code").entity(String.class).isEqualTo("FORBIDDEN");
		assertThat(analysis.calls("reanalyze")).isZero();
	}

	/** The stream ends at once with the error, before any event (GraphQlSubscriptionExceptionResolver). */
	@Test
	void subscribingToAnotherUsersInterviewIsRefused() {
		Flux<GraphQlTester.Response> events = graphQlTester
				.document("subscription ($id: ID!) { interviewEvents(sessionId: $id) { type } }")
				.variable("id", theirSession.id()).executeSubscription().toFlux();

		assertThatThrownBy(() -> events.blockFirst(Duration.ofSeconds(5)))
				.isInstanceOfSatisfying(SubscriptionPublisherException.class, ex -> assertThat(ex.getErrors())
						.singleElement().satisfies(error -> {
							assertThat(error.getExtensions()).containsEntry("code", "FORBIDDEN");
							assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
							assertThat(error.getMessage()).isEqualTo("This interview belongs to another user.");
						}));
	}

	/** An unknown id keeps its meaning: null for a query, NOT_FOUND for a mutation. */
	@Test
	void unknownIdsAreStillNotFoundOrNull() {
		graphQlTester.document("query ($id: ID!) { interviewSession(id: $id) { id } }")
				.variable("id", UUID.randomUUID()).execute()
				.errors().verify()
				.path("interviewSession").valueIsNull();
		graphQlTester.document("""
				mutation ($input: StartInterviewInput!) { startInterview(input: $input) { errors { code } } }
				""").variable("input", Map.of("projectId", UUID.randomUUID())).execute()
				.path("startInterview.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND");
	}

	private static void assertForbidden(List<ResponseError> errors, String message) {
		assertThat(errors).singleElement().satisfies(error -> {
			assertThat(error.getExtensions()).containsEntry("code", "FORBIDDEN");
			assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
			assertThat(error.getMessage()).isEqualTo(message);
		});
	}

}
