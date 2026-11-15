package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Task 4.1 step 3a: {@code InterviewApi} over HTTP, as the GraphQL gateway will see it from step 3b. The request
 * bodies are written out as JSON on purpose: they are the wire format of the contract.
 */
@IntegrationTest
class InterviewApiControllerTest {

	private static final String CONTROLLER = "src/main/java/com/example/shop/order/OrderController.java";

	private final MockMvc mvc;

	private final FakeAnalysisApi fixture;

	private final FakeLlmGateway llm;

	private final JdbcTemplate jdbc;

	private UUID project;

	InterviewApiControllerTest(@Autowired WebApplicationContext context, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeLlmGateway llm, @Autowired JdbcTemplate jdbc) {
		this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
		this.fixture = fixture;
		this.llm = llm;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void readyProject() {
		jdbc.update("DELETE FROM interview_session");   // cascades to topics, questions and answers
		fixture.reset();
		llm.reset();
		project = fixture.readyProject();
	}

	@Test
	void startReturnsTheSessionAndTheFirstQuestionWithoutItsRubric() throws Exception {
		llm.enqueue(LlmPurpose.QUESTION, question("Why does refund take a RefundRequest body?"));

		start("""
				{"projectId": "%s", "difficulty": "MID", "focusAreas": ["SECURITY"], "questionCount": 3}
				""".formatted(project))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.session.projectId").value(project.toString()))
				.andExpect(jsonPath("$.session.status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.session.difficulty").value("MID"))
				.andExpect(jsonPath("$.session.focusAreas", contains("SECURITY")))
				.andExpect(jsonPath("$.session.plannedQuestionCount").value(3))
				.andExpect(jsonPath("$.session.startedAt").isString())
				.andExpect(jsonPath("$.firstQuestion.order").value(1))
				.andExpect(jsonPath("$.firstQuestion.followUp").value(false))
				.andExpect(jsonPath("$.firstQuestion.codeRefs[0].path").value(CONTROLLER))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(content().string(not(containsString("SECRET"))));
	}

	@Test
	void aFailureBeforeTheSessionExistsIsAProblemDetail() throws Exception {
		start("""
				{"projectId": "%s"}
				""".formatted(UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.field").value("projectId"));
	}

	@Test
	void aFailureAfterTheSessionExistsComesBackWithTheFailedSession() throws Exception {
		for (int i = 0; i < 6; i++) {   // never grounded: the start fails after the session is saved
			llm.enqueue(LlmPurpose.QUESTION, new QuestionOutput("Why?",
					List.of(new CodeRefOutput("src/Nowhere.java", 1, 2)), null, List.of()));
		}

		start("""
				{"projectId": "%s"}
				""".formatted(project))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.session.status").value("FAILED"))
				.andExpect(jsonPath("$.session.failureReason").isString())
				.andExpect(jsonPath("$.firstQuestion").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("LLM_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").isString());
	}

	@Test
	void anAnswerComesBackGradedWithItsOutlineAndTheFollowUp() throws Exception {
		String first = startedQuestion();
		llm.enqueue(LlmPurpose.EVALUATION, new EvaluationOutput(5, List.of("validation"), List.of("error handling"),
				List.of(), "Good start.", true, "Check bad input."));
		llm.enqueue(LlmPurpose.FOLLOW_UP, question("What if the body is missing?"));

		String body = answer(first, """
				{"text": "It is validated first."}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer.questionId").value(first))
				.andExpect(jsonPath("$.answer.text").value("It is validated first."))
				.andExpect(jsonPath("$.answer.evaluation.score").value(5))
				.andExpect(jsonPath("$.answer.evaluation.verdict").value("ADEQUATE"))
				.andExpect(jsonPath("$.answer.evaluation.idealAnswerOutline", contains("SECRET OUTLINE")))
				.andExpect(jsonPath("$.nextQuestion.order").value(2))
				.andExpect(jsonPath("$.nextQuestion.followUp").value(true))
				.andExpect(jsonPath("$.nextQuestion.parentQuestionId").value(first))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andReturn().getResponse().getContentAsString();
		String next = JsonPath.read(body, "$.nextQuestion.id");
		assertThat((String) JsonPath.read(body, "$.session.currentQuestionId")).isEqualTo(next);
	}

	@Test
	void anEmptyAnswerIsA422OnItsField() throws Exception {
		String first = startedQuestion();

		answer(first, """
				{"text": "  "}
				""")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("ANSWER_EMPTY"))
				.andExpect(jsonPath("$.field").value("text"));
	}

	@Test
	void theBatchReadsReturnWhatExistsAndLeaveUnknownIdsOut() throws Exception {
		String first = startedQuestion();
		String session = jdbc.queryForObject("SELECT session_id::text FROM question WHERE id = ?::uuid", String.class,
				first);
		llm.enqueue(LlmPurpose.EVALUATION, new EvaluationOutput(9, List.of(), List.of(), List.of(), "Great.", false,
				""));
		llm.enqueue(LlmPurpose.QUESTION, question("What stops a refund larger than what was paid?"));
		String second = JsonPath.read(answer(first, """
				{"text": "It is validated first."}
				""").andReturn().getResponse().getContentAsString(), "$.nextQuestion.id");
		String unknown = UUID.randomUUID().toString();

		mvc.perform(get("/internal/sessions").param("ids", session, unknown))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(session));
		mvc.perform(get("/internal/sessions/by-project").param("projectIds", project.toString(), unknown))
				.andExpect(jsonPath("$[*].id", contains(session)));
		mvc.perform(get("/internal/questions/asked").param("sessionIds", session))
				.andExpect(jsonPath("$[*].id", contains(first, second)))
				.andExpect(jsonPath("$[*].order", contains(1, 2)));
		mvc.perform(get("/internal/questions").param("ids", second, unknown))
				.andExpect(jsonPath("$[*].id", contains(second)));
		mvc.perform(get("/internal/answers").param("questionIds", first, second))
				.andExpect(jsonPath("$[*].questionId", contains(first)))
				.andExpect(jsonPath("$[0].evaluation.score").value(9));
	}

	@Test
	void endingReturnsTheEndedSessionAndAnUnknownSessionIsA404() throws Exception {
		String first = startedQuestion();
		String session = jdbc.queryForObject("SELECT session_id::text FROM question WHERE id = ?::uuid", String.class,
				first);

		mvc.perform(post("/internal/sessions/{id}/end", session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.session.id").value(session))
				.andExpect(jsonPath("$.session.status").value("ABANDONED"))
				.andExpect(jsonPath("$.session.completedAt").isString());
		mvc.perform(post("/internal/sessions/{id}/end", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.field").value("sessionId"));
	}

	/** Starts an interview on the ready project and returns the first question's id. */
	private String startedQuestion() throws Exception {
		llm.enqueue(LlmPurpose.QUESTION, question("Why does refund take a RefundRequest body?"));
		String body = start("""
				{"projectId": "%s"}
				""".formatted(project)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.firstQuestion.id");
	}

	private ResultActions start(String json) throws Exception {
		return mvc.perform(post("/internal/sessions").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions answer(String questionId, String json) throws Exception {
		return mvc.perform(post("/internal/questions/{id}/answer", questionId).contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	private static QuestionOutput question(String text) {
		return new QuestionOutput(text, List.of(new CodeRefOutput(CONTROLLER, 27, 30)),
				new RubricOutput(List.of("SECRET RUBRIC POINT"), List.of(), List.of()), List.of("SECRET OUTLINE"));
	}

}
