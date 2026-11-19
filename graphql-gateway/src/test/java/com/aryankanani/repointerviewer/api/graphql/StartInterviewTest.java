package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.Difficulty;
import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.ResultError;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionStatus;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 1.10: {@code Mutation.startInterview} through the GraphQL engine. Since task 4.1 the interview itself is
 * interview-service's ({@link FakeInterviewApi} here; its rules are tested there), so this checks what the app does:
 * the request it sends, the payload it maps, the errors it reports.
 */
@IntegrationTest
class StartInterviewTest {

	private static final String START = """
			mutation ($input: StartInterviewInput!) {
			  startInterview(input: $input) {
			    session { id status difficulty focusAreas plannedQuestionCount failureReason }
			    firstQuestion { id order category text isFollowUp codeRefs { path startLine endLine } }
			    errors { code message field }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	private UUID project;

	StartInterviewTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.interviews = interviews;
	}

	@BeforeEach
	void readyProject() {
		fixture.reset();
		interviews.reset();
		project = fixture.readyProject();
	}

	@Test
	void startSendsTheInputAndReturnsTheSessionAndTheFirstQuestion() {
		SessionDto session = new SessionDto(UUID.randomUUID(), TestUsers.CANDIDATE, project, SessionStatus.IN_PROGRESS,
				Difficulty.MID,
				List.of(QuestionCategory.SECURITY), 3, null, null, FakeInterviewApi.session(project).startedAt(), null,
				null);
		startsWith(session);

		GraphQlTester.Response response = start(Map.of("projectId", project, "difficulty", "MID",
				"focusAreas", List.of("SECURITY"), "questionCount", 3));

		assertThat(interviews.lastStart()).isEqualTo(new StartInterviewRequest(TestUsers.CANDIDATE, project,
				Difficulty.MID, List.of(QuestionCategory.SECURITY), 3));   // task 4.4: the caller is the session's user
		response.path("startInterview.errors").entityList(Object.class).hasSize(0);
		response.path("startInterview.session.status").entity(String.class).isEqualTo("IN_PROGRESS");
		response.path("startInterview.session.difficulty").entity(String.class).isEqualTo("MID");
		response.path("startInterview.session.focusAreas").entityList(String.class).containsExactly("SECURITY");
		response.path("startInterview.session.plannedQuestionCount").entity(Integer.class).isEqualTo(3);
		response.path("startInterview.firstQuestion.order").entity(Integer.class).isEqualTo(1);
		response.path("startInterview.firstQuestion.category").entity(String.class).isEqualTo("ARCHITECTURE");
		response.path("startInterview.firstQuestion.isFollowUp").entity(Boolean.class).isEqualTo(false);
		response.path("startInterview.firstQuestion.codeRefs[0].path").entity(String.class)
				.isEqualTo(FakeInterviewApi.CONTROLLER);
		response.path("startInterview.firstQuestion.codeRefs[0].startLine").entity(Integer.class).isEqualTo(27);
	}

	/** Omitted input gets the schema's defaults (JUNIOR, 5 questions); no focus areas means balanced. */
	@Test
	void omittedInputIsSentWithTheSchemasDefaults() {
		startsWith(FakeInterviewApi.session(project));

		start(Map.of("projectId", project));

		assertThat(interviews.lastStart().projectId()).isEqualTo(project);
		assertThat(interviews.lastStart().difficulty()).isEqualTo(Difficulty.JUNIOR);
		assertThat(interviews.lastStart().questionCount()).isEqualTo(5);
		assertThat(interviews.lastStart().focusAreas()).isNullOrEmpty();
	}

	@Test
	void theSessionCanBeReadBackWithItsProjectAndQuestions() {
		startsWith(FakeInterviewApi.session(project));
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
				.path("interviewSession.questions[0].text").entity(String.class).isEqualTo("Question 1")
				.path("interviewSession.currentQuestion.order").entity(Integer.class).isEqualTo(1);

		graphQlTester.document("query ($id: ID!) { project(id: $id) { sessions { id status } } }")
				.variable("id", project).execute()
				.path("project.sessions[0].id").entity(String.class).isEqualTo(sessionId);
	}

	/**
	 * The rubric would give the answer away: the schema has no field for it (and interview-service never sends it,
	 * see its {@code InterviewApiControllerTest}).
	 */
	@Test
	void theRubricIsNeverExposed() {
		graphQlTester.document("""
				query ($id: ID!) { interviewSession(id: $id) { currentQuestion { rubric } } }
				""").variable("id", UUID.randomUUID()).execute()
				.errors().satisfy(errors -> assertThat(errors).anyMatch(error -> error.getMessage().contains("rubric")));
	}

	/** A failure before any session exists: interview-service's problem detail, an error on the input field. */
	@Test
	void anInvalidQuestionCountIsAnErrorOnItsField() {
		interviews.failNextCall(new DomainException(ErrorCode.INVALID_QUESTION_COUNT,
				"Choose between 3 and 8 questions.", "questionCount"));

		GraphQlTester.Response response = start(Map.of("projectId", project, "questionCount", 9));

		response.path("startInterview.session").valueIsNull();
		response.path("startInterview.errors[0].code").entity(String.class).isEqualTo("INVALID_QUESTION_COUNT");
		response.path("startInterview.errors[0].field").entityList(String.class).containsExactly("input", "questionCount");
	}

	/** A failure after the session was saved comes inside the result: the client still sees the FAILED session. */
	@Test
	void aFailedStartStillReturnsTheFailedSession() {
		SessionDto started = FakeInterviewApi.session(project);
		SessionDto failed = new SessionDto(started.id(), started.userId(), project, SessionStatus.FAILED,
				started.difficulty(),
				started.focusAreas(), started.plannedQuestionCount(), "Could not write a question grounded in this "
						+ "repository's code. Try again.", null, started.startedAt(), null, null);
		interviews.onStart(new StartResponse(failed, null,
				new ResultError("LLM_UNAVAILABLE", failed.failureReason(), null)));

		GraphQlTester.Response response = start(Map.of("projectId", project));

		response.path("startInterview.session.status").entity(String.class).isEqualTo("FAILED");
		response.path("startInterview.session.failureReason").entity(String.class).satisfies(
				reason -> assertThat(reason).startsWith("Could not write a question grounded"));
		response.path("startInterview.firstQuestion").valueIsNull();
		response.path("startInterview.errors[0].code").entity(String.class).isEqualTo("LLM_UNAVAILABLE");
		response.path("startInterview.errors[0].field").valueIsNull();
	}

	/** Queues a start that returns {@code session} asking its first question. */
	private void startsWith(SessionDto session) {
		QuestionDto first = FakeInterviewApi.question(session.id(), 1, null);
		interviews.onStart(new StartResponse(FakeInterviewApi.asking(session, first.id()), first, null));
	}

	private GraphQlTester.Response start(Map<String, Object> input) {
		return graphQlTester.document(START).variable("input", input).execute();
	}

}
