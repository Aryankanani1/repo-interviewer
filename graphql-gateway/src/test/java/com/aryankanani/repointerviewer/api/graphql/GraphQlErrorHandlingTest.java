package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Task 1.14 acceptance tests: every kind of failure reaches the client in a safe, predictable shape, through the real
 * GraphQL engine. No stack trace, exception text, SQL or prompt text in any response.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class GraphQlErrorHandlingTest {

	/** Bits of server internals, or of the services behind this app, that must never reach a client. */
	private static final List<String> NEVER_IN_A_RESPONSE = List.of("Exception", "at com.", "at org.", "select ",
			"SELECT ", "SECRET PROMPT TEXT", "NOT_A_CATEGORY", "interview-service", "Connection refused");

	private static final String INTERVIEW_UNAVAILABLE = "The interview service is not available right now. "
			+ "Try again in a minute.";

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	GraphQlErrorHandlingTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.interviews = interviews;
	}

	@BeforeEach
	void resetState() {
		fixture.reset();
		interviews.reset();
	}

	/**
	 * A bug on the other side (task 4.1): interview-service answers 500 with its internals in the body, not a problem
	 * detail. Spring's exception carries that body in its message; none of it may reach the client.
	 */
	@Test
	void anUnexpectedFailureIsInternalWithACorrelationIdAndNothingElse(CapturedOutput log) {
		interviews.failNextCall(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
				"Internal Server Error", HttpHeaders.EMPTY, """
						{"error": "IllegalArgumentException: No enum constant NOT_A_CATEGORY",
						 "trace": "at com.aryankanani.repointerviewer.interview.InterviewSession", "sql": "SELECT *"}
						""".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

		graphQlTester.document("query ($id: ID!) { interviewSession(id: $id) { focusAreas } }")
				.variable("id", UUID.randomUUID()).execute()
				.errors().satisfy(errors -> {
					ResponseError error = single(errors);
					assertThat(error.getExtensions()).containsEntry("code", "INTERNAL").containsKey("correlationId");
					String correlationId = (String) error.getExtensions().get("correlationId");
					assertThat(error.getMessage())
							.isEqualTo("Something went wrong on our side. Reference: " + correlationId);
					assertSafe(errors);
					assertThat(log.getOut()).contains("correlationId " + correlationId);   // findable in the log
				});
	}

	/**
	 * interview-service unreachable (task 4.2): its client's guard gives up with SERVICE_UNAVAILABLE, which a mutation
	 * returns in its payload, with a message that names the service and says nothing about where or why.
	 */
	@Test
	void anUnreachableServiceIsServiceUnavailableInTheMutationsPayload() {
		UUID project = fixture.readyProject();
		interviews.failNextCall(unreachable());

		graphQlTester.document("""
				mutation ($input: StartInterviewInput!) {
				  startInterview(input: $input) { session { id } errors { code message } }
				}
				""").variable("input", Map.of("projectId", project)).execute()
				.errors().verify()
				.path("startInterview.session").valueIsNull()
				.path("startInterview.errors[0].code").entity(String.class).isEqualTo("SERVICE_UNAVAILABLE")
				.path("startInterview.errors[0].message").entity(String.class).isEqualTo(INTERVIEW_UNAVAILABLE);
	}

	/** A query has no payload: a top-level error with the code, and the same message. */
	@Test
	void anUnreachableServiceIsATopLevelServiceUnavailableInAQuery() {
		interviews.failNextCall(unreachable());

		graphQlTester.document("query ($id: ID!) { interviewSession(id: $id) { id } }")
				.variable("id", UUID.randomUUID()).execute()
				.errors().satisfy(errors -> {
					ResponseError error = single(errors);
					assertThat(error.getExtensions()).containsEntry("code", "SERVICE_UNAVAILABLE");
					assertThat(error.getMessage()).isEqualTo(INTERVIEW_UNAVAILABLE);
					assertSafe(errors);
				});
	}

	@Test
	void anIdThatIsNotAUuidIsInvalidInputNotInternal() {
		graphQlTester.document("query { interviewSession(id: \"not-a-uuid\") { id } }").execute()
				.errors().satisfy(errors -> {
					ResponseError error = single(errors);
					assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT");
					assertThat(error.getMessage()).startsWith("Invalid value for id");
					assertSafe(errors);
				});
	}

	@Test
	void anAbsurdlyLongUrlIsInvalidInputOnItsField() {
		graphQlTester.document("""
				mutation ($input: SubmitProjectInput!) { submitProject(input: $input) { project { id } errors { code message field } } }
				""").variable("input", Map.of("githubUrl", "https://github.com/" + "a".repeat(400))).execute()
				.path("submitProject.project").valueIsNull()
				.path("submitProject.errors[0].code").entity(String.class).isEqualTo("INVALID_INPUT")
				.path("submitProject.errors[0].message").entity(String.class)
				.isEqualTo("githubUrl must be at most 300 characters.")
				.path("submitProject.errors[0].field").entityList(String.class).containsExactly("input", "githubUrl");
	}

	@Test
	void anAbsurdlyLongAnswerIsInvalidInputBeforeAnyWork() {
		graphQlTester.document("""
				mutation ($input: SubmitAnswerInput!) { submitAnswer(input: $input) { answer { id } errors { code field } } }
				""").variable("input", Map.of("questionId", UUID.randomUUID(), "text", "x".repeat(25_000))).execute()
				.path("submitAnswer.errors[0].code").entity(String.class).isEqualTo("INVALID_INPUT")
				.path("submitAnswer.errors[0].field").entityList(String.class).containsExactly("input", "text");
	}

	/** What the interview client's {@code ServiceCallGuard} throws when interview-service refuses the connection. */
	private static ServiceUnavailableException unreachable() {
		return new ServiceUnavailableException(INTERVIEW_UNAVAILABLE,
				new ConnectException("Connection refused: interview-service:8080"));
	}

	private static ResponseError single(List<ResponseError> errors) {
		assertThat(errors).hasSize(1);
		return errors.getFirst();
	}

	private static void assertSafe(List<ResponseError> errors) {
		for (ResponseError error : errors) {
			String visible = error.getMessage() + " " + error.getExtensions();
			NEVER_IN_A_RESPONSE.forEach(secret -> assertThat(visible).doesNotContain(secret));
		}
	}

}
