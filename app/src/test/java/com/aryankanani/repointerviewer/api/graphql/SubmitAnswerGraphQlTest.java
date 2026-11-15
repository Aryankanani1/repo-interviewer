package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.ResultError;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.SubmitAnswerResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 1.11: {@code Mutation.submitAnswer} and the answer and follow-up links, through the GraphQL engine. Since task
 * 4.1 grading is interview-service's ({@link FakeInterviewApi} here); this checks what the app sends and maps.
 */
@IntegrationTest
class SubmitAnswerGraphQlTest {

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

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	private SessionDto session;

	private QuestionDto first;

	SubmitAnswerGraphQlTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.interviews = interviews;
	}

	@BeforeEach
	void sessionAskingItsFirstQuestion() {
		fixture.reset();
		interviews.reset();
		session = FakeInterviewApi.session(fixture.readyProject());
		first = interviews.add(FakeInterviewApi.question(session.id(), 1, null));
		session = interviews.add(FakeInterviewApi.asking(session, first.id()));
	}

	@Test
	void theAnswerComesBackGradedWithTheIdealOutlineAndTheFollowUp() {
		QuestionDto followUp = FakeInterviewApi.question(session.id(), 2, first.id());
		interviews.onSubmitAnswer(new SubmitAnswerResponse(
				FakeInterviewApi.graded(first.id(), "It is validated first.", 5), followUp,
				FakeInterviewApi.asking(session, followUp.id()), null));

		GraphQlTester.Response response = submit(first.id(), "It is validated first.");

		assertThat(interviews.lastAnswer().text()).isEqualTo("It is validated first.");
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
		response.path("submitAnswer.nextQuestion.parent.id").entity(String.class).isEqualTo(first.id().toString());
		response.path("submitAnswer.session.status").entity(String.class).isEqualTo("IN_PROGRESS");
	}

	/** With Kafka the grade and the next question come later (the subscription): both null in the payload. */
	@Test
	void anAnswerGradedLaterComesBackWithoutAnEvaluationOrANextQuestion() {
		interviews.onSubmitAnswer(new SubmitAnswerResponse(
				FakeInterviewApi.ungraded(first.id(), "It is validated first."), null, session, null));

		GraphQlTester.Response response = submit(first.id(), "It is validated first.");

		response.path("submitAnswer.errors").entityList(Object.class).hasSize(0);
		response.path("submitAnswer.answer.text").entity(String.class).isEqualTo("It is validated first.");
		response.path("submitAnswer.answer.evaluation").valueIsNull();
		response.path("submitAnswer.nextQuestion").valueIsNull();
	}

	/** The answer is saved before grading can fail, so it comes back next to the error. */
	@Test
	void aGradingFailureIsAnErrorNextToTheSavedAnswer() {
		interviews.onSubmitAnswer(new SubmitAnswerResponse(
				FakeInterviewApi.ungraded(first.id(), "It is validated first."), null, session,
				new ResultError("LLM_UNAVAILABLE", "The AI model is not available right now.", null)));

		GraphQlTester.Response response = submit(first.id(), "It is validated first.");

		response.path("submitAnswer.answer.text").entity(String.class).isEqualTo("It is validated first.");
		response.path("submitAnswer.errors[0].code").entity(String.class).isEqualTo("LLM_UNAVAILABLE");
	}

	@Test
	void theSessionShowsEachQuestionWithItsAnswer() {
		QuestionDto second = interviews.add(FakeInterviewApi.question(session.id(), 2, null));
		interviews.add(FakeInterviewApi.graded(first.id(), "It is validated first.", 9));
		interviews.add(FakeInterviewApi.asking(session, second.id()));

		graphQlTester.document("""
				query ($id: ID!) {
				  interviewSession(id: $id) { questions { order answer { evaluation { score } } } }
				}
				""").variable("id", session.id()).execute()
				.path("interviewSession.questions").entityList(Object.class).hasSize(2)
				.path("interviewSession.questions[0].answer.evaluation.score").entity(Integer.class).isEqualTo(9)
				.path("interviewSession.questions[1].answer").valueIsNull();
	}

	@Test
	void anEmptyAnswerIsAnErrorOnTheTextField() {
		interviews.failNextCall(new DomainException(ErrorCode.ANSWER_EMPTY, "Write an answer first.", "text"));

		GraphQlTester.Response response = submit(first.id(), "  ");

		response.path("submitAnswer.answer").valueIsNull();
		response.path("submitAnswer.errors[0].code").entity(String.class).isEqualTo("ANSWER_EMPTY");
		response.path("submitAnswer.errors[0].field").entityList(String.class).containsExactly("input", "text");
	}

	private GraphQlTester.Response submit(UUID questionId, String text) {
		return graphQlTester.document(SUBMIT).variable("input", Map.of("questionId", questionId, "text", text))
				.execute();
	}

}
