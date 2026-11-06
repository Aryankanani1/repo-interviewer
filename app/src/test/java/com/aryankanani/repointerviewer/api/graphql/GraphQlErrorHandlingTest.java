package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.14 acceptance tests: every kind of failure reaches the client in a safe, predictable shape, through the real
 * GraphQL engine. No stack trace, exception text, SQL or prompt text in any response.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class GraphQlErrorHandlingTest {

	/** Bits of server internals that must never reach a client. */
	private static final List<String> NEVER_IN_A_RESPONSE = List.of("Exception", "at com.", "at org.", "select ",
			"SELECT ", "SECRET PROMPT TEXT", "NOT_A_CATEGORY");

	private final GraphQlTester graphQlTester;

	private final FixtureIndexer fixture;

	private final FakeLlmGateway llm;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	GraphQlErrorHandlingTest(@Autowired GraphQlTester graphQlTester, @Autowired FixtureIndexer fixture,
			@Autowired FakeLlmGateway llm, @Autowired FakeEmbeddingModel embeddingModel, @Autowired JdbcTemplate jdbc) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.llm = llm;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void resetState() {
		jdbc.update("DELETE FROM project");
		llm.reset();
		embeddingModel.reset();
	}

	/** A real bug path, no mocks: a stored JSON value Hibernate cannot read. */
	@Test
	void anUnexpectedFailureIsInternalWithACorrelationIdAndNothingElse(CapturedOutput log) {
		UUID session = jdbc.queryForObject("""
				INSERT INTO interview_session (project_id, status, difficulty, planned_question_count, focus_areas)
				VALUES (?, 'IN_PROGRESS', 'JUNIOR', 3, '["NOT_A_CATEGORY"]') RETURNING id
				""", UUID.class, fixture.newProject());

		graphQlTester.document("query ($id: ID!) { interviewSession(id: $id) { focusAreas } }")
				.variable("id", session).execute()
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

	/** A failure while calling the model: its message never reaches the client. */
	@Test
	void textFromAFailedModelCallNeverReachesTheClient() {
		UUID project = fixture.readyProject();
		llm.enqueueFailure(LlmPurpose.PLAN, new IllegalStateException("upstream said: SECRET PROMPT TEXT"));

		graphQlTester.document("""
				mutation ($input: StartInterviewInput!) { startInterview(input: $input) { session { id } errors { code } } }
				""").variable("input", Map.of("projectId", project)).execute()
				.errors().satisfy(errors -> {
					assertThat(single(errors).getExtensions()).containsEntry("code", "INTERNAL");
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
