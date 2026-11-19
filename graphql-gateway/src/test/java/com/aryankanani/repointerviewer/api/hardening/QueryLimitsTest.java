package com.aryankanani.repointerviewer.api.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 4.6 acceptance tests: a query deeper than {@code app.graphql.max-depth} (10) or with more fields than
 * {@code max-complexity} (200) is refused with INVALID_INPUT before any field runs, so neither service is called. Each
 * limit itself still runs. The schema's cycle {@code InterviewSession.project.sessions.project...} makes a query as
 * deep as the client likes; aliases repeat a field as often as it likes.
 */
@IntegrationTest
class QueryLimitsTest {

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi analysis;

	private final FakeInterviewApi interviews;

	private SessionDto session;

	QueryLimitsTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
		this.interviews = interviews;
	}

	@BeforeEach
	void aSessionOfTheCandidate() {
		analysis.reset();
		interviews.reset();
		session = interviews.add(FakeInterviewApi.session(analysis.readyProject()));
	}

	@Test
	void aQueryTenLevelsDeepRuns() {
		graphQlTester.document(sessionProjectCycle(10)).variable("id", session.id()).execute()
				.errors().verify()
				.path("interviewSession.project.sessions[0].project.sessions[0].project.sessions[0].project.sessions[0].id")
				.entity(String.class).isEqualTo(session.id().toString());
	}

	@Test
	void elevenLevelsAreRefusedBeforeAnyServiceIsCalled() {
		graphQlTester.document(sessionProjectCycle(11)).variable("id", session.id()).execute()
				.errors().satisfy(errors -> assertThat(errors).singleElement().satisfies(error -> {
					assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT");
					assertThat(error.getMessage()).isEqualTo("The query is too deep: 11 levels of fields, at most 10.");
				}))
				.path("interviewSession").pathDoesNotExist();
		assertNoServiceCalled();
	}

	@Test
	void twoHundredFieldsRun() {
		graphQlTester.document(aliasedIds(199)).variable("id", session.id()).execute()   // 199 + interviewSession
				.errors().verify()
				.path("interviewSession.id199").entity(String.class).isEqualTo(session.id().toString());
	}

	@Test
	void twoHundredAndOneFieldsAreRefusedBeforeAnyServiceIsCalled() {
		graphQlTester.document(aliasedIds(200)).variable("id", session.id()).execute()
				.errors().satisfy(errors -> assertThat(errors).singleElement().satisfies(error -> {
					assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT");
					assertThat(error.getMessage()).isEqualTo("The query asks for too much: 201 fields, at most 200.");
				}))
				.path("interviewSession").pathDoesNotExist();
		assertNoServiceCalled();
	}

	/** The deepest and largest operation on the list of trusted documents fits both limits. */
	@Test
	void theLargestTrustedDocumentRuns() throws Exception {
		QuestionDto main = interviews.add(FakeInterviewApi.question(session.id(), 1, null));
		QuestionDto followUp = interviews.add(FakeInterviewApi.question(session.id(), 2, main.id()));
		interviews.add(FakeInterviewApi.graded(followUp.id(), "Both read the same balance.", 6));

		graphQlTester.document(new ClassPathResource("graphql-documents/InterviewSession.graphql")
				.getContentAsString(StandardCharsets.UTF_8)).variable("id", session.id()).execute()
				.errors().verify()
				.path("interviewSession.questions[1].parent.id").entity(String.class).isEqualTo(main.id().toString())
				.path("interviewSession.questions[1].answer.evaluation.score").entity(Integer.class).isEqualTo(6);
	}

	/**
	 * {@code interviewSession { project { sessions { project ... } } } }, {@code levels} levels of fields counted the
	 * way graphql-java counts them: {@code interviewSession} is 1, the innermost {@code id} is {@code levels}.
	 */
	private static String sessionProjectCycle(int levels) {
		StringBuilder fields = new StringBuilder("id");
		for (int level = levels - 1; level >= 2; level--) {
			fields = new StringBuilder(level % 2 == 0 ? "project { " : "sessions { ").append(fields).append(" }");
		}
		return "query ($id: ID!) { interviewSession(id: $id) { " + fields + " } }";
	}

	/** {@code interviewSession { id1: id id2: id ... } }: {@code count} + 1 fields. */
	private static String aliasedIds(int count) {
		return "query ($id: ID!) { interviewSession(id: $id) { "
				+ IntStream.rangeClosed(1, count).mapToObj(i -> "id" + i + ": id").collect(Collectors.joining(" "))
				+ " } }";
	}

	private void assertNoServiceCalled() {
		assertThat(interviews.calls("sessions")).isZero();
		assertThat(analysis.calls("findByIds")).isZero();
		assertThat(interviews.calls("sessionsByProject")).isZero();
	}

}
