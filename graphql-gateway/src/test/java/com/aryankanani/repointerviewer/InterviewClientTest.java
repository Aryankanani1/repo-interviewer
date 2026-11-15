package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.AnswerVerdict;
import com.aryankanani.repointerviewer.contracts.interview.CodeRef;
import com.aryankanani.repointerviewer.contracts.interview.Difficulty;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import com.aryankanani.repointerviewer.contracts.interview.ResultError;
import com.aryankanani.repointerviewer.contracts.interview.SessionStatus;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.contracts.interview.SubmitAnswerResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * The client half of the interview contract (task 4.1 step 3b; the server half is interview-service's
 * {@code InterviewApiControllerTest}). A small context with Boot's HTTP client auto-configuration and
 * {@link InterviewClientConfiguration}, as in the app, and a {@link MockRestServiceServer} in place of the network.
 */
class InterviewClientTest {

	private static final String BASE = "http://interview.test";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
					ImperativeHttpClientAutoConfiguration.class, HttpServiceClientPropertiesAutoConfiguration.class,
					RestClientAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
			.withUserConfiguration(InterviewClientConfiguration.class, MockServerConfiguration.class)
			.withPropertyValues("spring.http.serviceclient.interview.base-url=" + BASE);

	@Test
	void aStartSendsTheRequestAsJsonAndReadsTheSessionAndItsFirstQuestion() {
		UUID project = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		UUID question = UUID.randomUUID();
		run((interviews, server) -> {
			server.expect(requestTo(BASE + "/internal/sessions"))
					.andExpect(method(HttpMethod.POST))
					.andExpect(content().json("""
							{"projectId": "%s", "difficulty": "MID", "focusAreas": ["SECURITY"], "questionCount": 3}
							""".formatted(project)))
					.andRespond(withSuccess("""
							{"session": {"id": "%s", "projectId": "%s", "status": "IN_PROGRESS", "difficulty": "MID",
							             "focusAreas": ["SECURITY"], "plannedQuestionCount": 3,
							             "startedAt": "2026-11-15T10:00:00Z", "currentQuestionId": "%s"},
							 "firstQuestion": {"id": "%s", "sessionId": "%s", "order": 1, "category": "SECURITY",
							                   "text": "Why?", "followUp": false,
							                   "codeRefs": [{"path": "A.java", "startLine": 1, "endLine": 2}],
							                   "askedAt": "2026-11-15T10:00:05Z"},
							 "error": null}
							""".formatted(session, project, question, question, session), MediaType.APPLICATION_JSON));

			StartResponse started = interviews.start(new StartInterviewRequest(project, Difficulty.MID,
					List.of(QuestionCategory.SECURITY), 3));

			assertThat(started.session().status()).isEqualTo(SessionStatus.IN_PROGRESS);
			assertThat(started.session().startedAt()).isEqualTo(Instant.parse("2026-11-15T10:00:00Z"));
			assertThat(started.session().currentQuestionId()).isEqualTo(question);
			assertThat(started.firstQuestion().codeRefs()).containsExactly(new CodeRef("A.java", 1, 2));
			assertThat(started.error()).isNull();
			server.verify();
		});
	}

	/** A failure after the answer was saved comes back as a result, next to the answer, not as an exception. */
	@Test
	void anAnswerIsPostedToItsQuestionAndAnErrorAfterItWasSavedIsPartOfTheResult() {
		UUID question = UUID.randomUUID();
		run((interviews, server) -> {
			server.expect(requestTo(BASE + "/internal/questions/" + question + "/answer"))
					.andExpect(method(HttpMethod.POST))
					.andExpect(content().json("""
							{"text": "It is validated first."}
							"""))
					.andRespond(withSuccess("""
							{"answer": {"id": "%s", "questionId": "%s", "text": "It is validated first.",
							            "submittedAt": "2026-11-15T10:01:00Z",
							            "evaluation": {"score": 5, "verdict": "ADEQUATE", "feedback": "Good start.",
							                           "coveredPoints": [], "missedPoints": ["error handling"],
							                           "incorrectClaims": [], "idealAnswerOutline": ["Validated"],
							                           "evaluatedAt": "2026-11-15T10:01:09Z"}},
							 "nextQuestion": null,
							 "session": {"id": "%s", "projectId": "%s", "status": "IN_PROGRESS", "difficulty": "JUNIOR",
							             "focusAreas": [], "plannedQuestionCount": 3,
							             "startedAt": "2026-11-15T10:00:00Z"},
							 "error": {"code": "LLM_UNAVAILABLE", "message": "Try again in a minute.", "field": null}}
							""".formatted(UUID.randomUUID(), question, UUID.randomUUID(), UUID.randomUUID()),
							MediaType.APPLICATION_JSON));

			SubmitAnswerResponse answered = interviews.submitAnswer(question,
					new AnswerRequest("It is validated first."));

			assertThat(answered.answer().evaluation().verdict()).isEqualTo(AnswerVerdict.ADEQUATE);
			assertThat(answered.answer().evaluation().idealAnswerOutline()).containsExactly("Validated");
			assertThat(answered.nextQuestion()).isNull();
			assertThat(answered.error()).isEqualTo(new ResultError("LLM_UNAVAILABLE", "Try again in a minute.", null));
			server.verify();
		});
	}

	/** A batch read sends its ids as one repeated query parameter. */
	@Test
	void aBatchReadSendsTheIdsAsARepeatedQueryParameter() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		run((interviews, server) -> {
			server.expect(requestTo(BASE + "/internal/answers?questionIds=" + first + "&questionIds=" + second))
					.andExpect(method(HttpMethod.GET))
					.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

			assertThat(interviews.answers(List.of(first, second))).isEmpty();
			server.verify();
		});
	}

	@Test
	void aProblemDetailIsTheSameDomainExceptionAsOnTheOtherSide() {
		UUID question = UUID.randomUUID();
		run((interviews, server) -> {
			server.expect(requestTo(BASE + "/internal/questions/" + question + "/answer"))
					.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
							.contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
									{"type": "about:blank", "title": "Unprocessable Content", "status": 422,
									 "detail": "Write an answer first.", "code": "ANSWER_EMPTY", "field": "text"}
									"""));

			assertThatThrownBy(() -> interviews.submitAnswer(question, new AnswerRequest("  ")))
					.isInstanceOfSatisfying(DomainException.class, ex -> {
						assertThat(ex.code()).isEqualTo(ErrorCode.ANSWER_EMPTY);
						assertThat(ex.getMessage()).isEqualTo("Write an answer first.");
						assertThat(ex.field()).contains("text");
					});
		});
	}

	private void run(BiConsumer<InterviewApi, MockRestServiceServer> test) {
		contextRunner.run(context -> test.accept(context.getBean(InterviewApi.class),
				context.getBean(MockServerConfiguration.class).server));
	}

	/** Binds a mock server to the interview group's RestClient, after the app's own configuration of it. */
	@Configuration(proxyBeanMethods = false)
	static class MockServerConfiguration {

		MockRestServiceServer server;

		@Bean
		RestClientHttpServiceGroupConfigurer mockInterviewServer() {
			return groups -> groups.filterByName(InterviewClientConfiguration.GROUP)
					.forEachClient((group, builder) -> server = MockRestServiceServer.bindTo(builder).build());
		}

	}

}
