package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.SqlStatementCounter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.13 acceptance test: one query for a session with every nested field costs the same number of SQL
 * statements whether the session has 3 questions or 8. Without batching, each question would add its own queries
 * (the N+1 problem); with {@code @BatchMapping} each field costs one query for all its parents.
 */
@IntegrationTest
class NPlusOneTest {

	private static final String SERVICE = "src/main/java/com/example/shop/order/OrderService.java";

	/** Every field that is resolved from the database, nested the way a client would ask for them. */
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

	private final GraphQlTester graphQlTester;

	private final SqlStatementCounter sql;

	private final FixtureIndexer fixture;

	private final FakeEmbeddingModel embeddingModel;

	private final JdbcTemplate jdbc;

	private UUID project;

	NPlusOneTest(@Autowired GraphQlTester graphQlTester, @Autowired SqlStatementCounter sql,
			@Autowired FixtureIndexer fixture, @Autowired FakeEmbeddingModel embeddingModel,
			@Autowired JdbcTemplate jdbc) {
		this.graphQlTester = graphQlTester;
		this.sql = sql;
		this.fixture = fixture;
		this.embeddingModel = embeddingModel;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void readyProject() {
		jdbc.update("DELETE FROM project");
		embeddingModel.reset();
		project = fixture.readyProject();
	}

	/** Acceptance criterion. */
	@Test
	void aSessionWithEightQuestionsCostsAsManyQueriesAsOneWithThree() {
		List<String> three = statementsFor(sessionWith(3));
		List<String> eight = statementsFor(sessionWith(8));

		System.out.printf("%nSQL statements for one session query: 3 questions = %d, 8 questions = %d%n%n",
				three.size(), eight.size());
		assertThat(eight).as("SQL for 8 questions:\n%s", String.join("\n", eight)).hasSameSizeAs(three);
		assertThat(eight).hasSizeLessThanOrEqualTo(10);   // one query per resolved field, plus the session itself
	}

	@Test
	void theSnippetOfACodeRefIsTheCitedSource() {
		graphQlTester.document(SESSION).variable("id", sessionWith(3)).execute()
				.path("interviewSession.questions[0].codeRefs[0].snippet").entity(String.class)
				.satisfies(snippet -> assertThat(snippet).startsWith("    /**\n     * Refunds part or all of a paid order.")
						.contains("public Order refund(UUID orderId, RefundRequest request) {"));
	}

	private List<String> statementsFor(UUID session) {
		sql.start();
		graphQlTester.document(SESSION).variable("id", session).execute()
				.path("interviewSession.questions").entityList(Object.class).satisfies(questions -> assertThat(questions)
						.isNotEmpty());
		return sql.stop();
	}

	/**
	 * A session written straight to the database: {@code count} asked questions, question 2 a follow-up on question
	 * 1, every question but the last answered and graded, each citing OrderService#refund (lines 27-42).
	 */
	private UUID sessionWith(int count) {
		UUID session = jdbc.queryForObject("""
				INSERT INTO interview_session (project_id, status, difficulty, planned_question_count)
				VALUES (?, 'IN_PROGRESS', 'JUNIOR', ?) RETURNING id
				""", UUID.class, project, Math.min(count, 8));
		UUID previous = null;
		UUID topic = null;
		UUID last = null;
		for (int order = 1; order <= count; order++) {
			boolean followUp = order == 2;
			if (!followUp) {
				topic = jdbc.queryForObject("""
						INSERT INTO interview_topic (session_id, order_index, category, title, status)
						VALUES (?, ?, 'ARCHITECTURE', ?, 'ASKED') RETURNING id
						""", UUID.class, session, order, "Topic " + order);
			}
			last = jdbc.queryForObject("""
					INSERT INTO question (session_id, topic_id, parent_question_id, order_index, category, text, is_follow_up,
					    code_refs, rubric, ideal_answer_outline, status, asked_at)
					VALUES (?, ?, ?, ?, 'ARCHITECTURE', ?, ?, CAST(? AS jsonb),
					    '{"mustMention": [], "goodToMention": [], "redFlags": []}', '["An outline point"]', ?, now())
					RETURNING id
					""", UUID.class, session, topic, followUp ? previous : null, order, "Question " + order, followUp,
					"[{\"path\": \"" + SERVICE + "\", \"startLine\": 27, \"endLine\": 42}]",
					order < count ? "ANSWERED" : "ASKED");
			if (order < count) {
				jdbc.update("""
						INSERT INTO answer (question_id, text, text_sha, score, verdict, feedback, covered_points,
						    missed_points, incorrect_claims, follow_up_needed, follow_up_reason, evaluated_at)
						VALUES (?, ?, ?, 7, 'ADEQUATE', 'Fine.', '[]', '[]', '[]', false, '', now())
						""", last, "Answer " + order, "sha" + order);
			}
			previous = last;
		}
		jdbc.update("UPDATE interview_session SET current_question_id = ? WHERE id = ?", last, session);
		return session;
	}

}
