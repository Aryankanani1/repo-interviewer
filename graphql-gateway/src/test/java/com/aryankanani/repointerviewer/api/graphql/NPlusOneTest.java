package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 1.13 acceptance test: a session query costs the same whatever the number of questions. Since task 4.1 the
 * data is behind two services, so the cost is counted in calls to their APIs, each one an HTTP request: one per
 * resolved field ({@code @BatchMapping}), never one per question. That each of those calls is a fixed number of SQL
 * queries is interview-service's {@code BatchReadsTest}.
 */
@IntegrationTest
class NPlusOneTest {

	/** Every field that comes from another service, nested the way a client would ask for them. */
	private static final String SESSION = """
			query ($id: ID!) {
			  interviewSession(id: $id) {
			    status
			    project { name sessions { id status } }
			    questions {
			      order text isFollowUp
			      parent { id }
			      codeRefs { path startLine endLine snippet }
			      answer { text evaluation { score verdict idealAnswerOutline } }
			    }
			    currentQuestion { id }
			  }
			}
			""";

	private static final List<String> INTERVIEW_READS = List.of("sessions", "sessionsByProject", "questions",
			"askedQuestions", "answers");

	private static final List<String> ANALYSIS_READS = List.of("findByIds", "snippets");

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi fixture;

	private final FakeInterviewApi interviews;

	private UUID project;

	NPlusOneTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi fixture,
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

	/** Acceptance criterion. */
	@Test
	void aSessionWithEightQuestionsCostsAsManyCallsAsOneWithThree() {
		Map<String, Integer> three = callsFor(sessionWith(3));
		Map<String, Integer> eight = callsFor(sessionWith(8));

		System.out.printf("%nService calls for one session query: 3 questions = %s, 8 questions = %s%n%n", three,
				eight);
		assertThat(eight).isEqualTo(three);
		assertThat(eight.values()).allMatch(calls -> calls <= 2);   // currentQuestion and parent both load questions
		assertThat(eight.values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(9);
	}

	/** The snippet comes from analysis-service, for the session's project and the cited lines. */
	@Test
	void theSnippetOfACodeRefIsTheCitedSource() {
		graphQlTester.document(SESSION).variable("id", sessionWith(3)).execute()
				.path("interviewSession.questions[0].codeRefs[0].snippet").entity(String.class)
				.satisfies(snippet -> assertThat(snippet).contains("@PostMapping(\"/{id}/refund\")")
						.contains("public Order refund(@PathVariable UUID id,"));
	}

	/** The calls one session query makes: the counters after it, minus before it. */
	private Map<String, Integer> callsFor(UUID session) {
		Map<String, Integer> before = counts();
		graphQlTester.document(SESSION).variable("id", session).execute()
				.path("interviewSession.questions").entityList(Object.class).satisfies(questions -> assertThat(questions)
						.isNotEmpty());
		Map<String, Integer> calls = new LinkedHashMap<>();
		counts().forEach((method, count) -> calls.put(method, count - before.get(method)));
		return calls;
	}

	/**
	 * A session of {@code count} asked questions, question 2 a follow-up on question 1, every question but the last
	 * answered and graded, each citing OrderController lines 27-30.
	 */
	private UUID sessionWith(int count) {
		SessionDto session = FakeInterviewApi.session(project);
		QuestionDto previous = null;
		QuestionDto last = null;
		for (int order = 1; order <= count; order++) {
			last = interviews.add(FakeInterviewApi.question(session.id(), order, order == 2 ? previous.id() : null));
			if (order < count) {
				interviews.add(FakeInterviewApi.graded(last.id(), "Answer " + order, 7));
			}
			previous = last;
		}
		interviews.add(FakeInterviewApi.asking(session, last.id()));
		return session.id();
	}

	private Map<String, Integer> counts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		INTERVIEW_READS.forEach(method -> counts.put("interview." + method, interviews.calls(method)));
		ANALYSIS_READS.forEach(method -> counts.put("analysis." + method, fixture.calls(method)));
		return counts;
	}

}
