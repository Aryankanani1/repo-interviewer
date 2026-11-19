package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.CategoryScore;
import com.aryankanani.repointerviewer.contracts.interview.EndResponse;
import com.aryankanani.repointerviewer.contracts.interview.InterviewReport;
import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import com.aryankanani.repointerviewer.contracts.interview.ResultError;
import com.aryankanani.repointerviewer.contracts.interview.ReviewTopic;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 1.12: {@code Mutation.endInterview} and {@code InterviewSession.report} through the GraphQL engine. Since task
 * 4.1 the report is interview-service's ({@link FakeInterviewApi} here; its numbers are tested there), so this checks
 * that every field of it reaches the client, and the errors.
 */
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

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	private SessionDto session;

	EndInterviewGraphQlTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.fixture = fixture;
		this.interviews = interviews;
	}

	@BeforeEach
	void runningSession() {
		fixture.reset();
		interviews.reset();
		session = interviews.add(FakeInterviewApi.session(fixture.readyProject()));
	}

	@Test
	void endingReturnsTheAbandonedSessionWithItsReport() {
		InterviewReport report = new InterviewReport(8.0, "You know the refund flow well.", List.of("Validation"),
				List.of("Concurrency"), List.of(new CategoryScore(QuestionCategory.ARCHITECTURE, 8.0, 1)),
				List.of(new ReviewTopic("Optimistic locking in the refund flow", "Two refunds can race.")));
		interviews.onEnd(new EndResponse(ended(report), null));

		GraphQlTester.Response response = end(session.id());

		response.path("endInterview.errors").entityList(Object.class).hasSize(0);
		response.path("endInterview.session.status").entity(String.class).isEqualTo("ABANDONED");
		response.path("endInterview.session.report.overallScore").entity(Double.class).isEqualTo(8.0);
		response.path("endInterview.session.report.summary").entity(String.class)
				.isEqualTo("You know the refund flow well.");
		response.path("endInterview.session.report.strengths").entityList(String.class).containsExactly("Validation");
		response.path("endInterview.session.report.weaknesses").entityList(String.class).containsExactly("Concurrency");
		response.path("endInterview.session.report.categoryScores[0].category").entity(String.class)
				.isEqualTo("ARCHITECTURE");
		response.path("endInterview.session.report.categoryScores[0].averageScore").entity(Double.class).isEqualTo(8.0);
		response.path("endInterview.session.report.categoryScores[0].questionCount").entity(Integer.class).isEqualTo(1);
		response.path("endInterview.session.report.topicsToReview[0].topic").entity(String.class)
				.isEqualTo("Optimistic locking in the refund flow");
	}

	/** The session is ended anyway; ending it again retries the report. */
	@Test
	void aReportThatCouldNotBeWrittenIsAnErrorNextToTheEndedSession() {
		interviews.onEnd(new EndResponse(ended(null),
				new ResultError("LLM_UNAVAILABLE", "The AI model is not available right now.", null)));

		GraphQlTester.Response response = end(session.id());

		response.path("endInterview.session.status").entity(String.class).isEqualTo("ABANDONED");
		response.path("endInterview.session.report").valueIsNull();
		response.path("endInterview.errors[0].code").entity(String.class).isEqualTo("LLM_UNAVAILABLE");
	}

	@Test
	void anUnknownSessionIsAnErrorOnItsField() {
		interviews.failNextCall(new DomainException(ErrorCode.NOT_FOUND, "Interview not found.", "sessionId"));

		GraphQlTester.Response response = end(UUID.randomUUID());

		response.path("endInterview.session").valueIsNull();
		response.path("endInterview.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND");
		response.path("endInterview.errors[0].field").entityList(String.class).containsExactly("input", "sessionId");
	}

	private SessionDto ended(InterviewReport report) {
		return new SessionDto(session.id(), session.userId(), session.projectId(), SessionStatus.ABANDONED,
				session.difficulty(),
				session.focusAreas(), session.plannedQuestionCount(), null, report, session.startedAt(),
				Instant.now(), null);
	}

	private GraphQlTester.Response end(UUID id) {
		return graphQlTester.document(END).variable("input", Map.of("sessionId", id)).execute();
	}

}
