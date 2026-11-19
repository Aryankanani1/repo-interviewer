package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.SqlStatementCounter;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.13's N+1 guarantee, interview side (task 4.1): every batch read of {@link InterviewApi} costs a fixed number
 * of SQL statements, whatever the number of questions. Together with the GraphQL app's {@code NPlusOneTest} (a fixed
 * number of calls per query) a session query still costs the same for 3 questions as for 8.
 */
@IntegrationTest
class BatchReadsTest {

	private static final String SERVICE = "src/main/java/com/example/shop/order/OrderService.java";

	private final InterviewApi interviews;

	private final SqlStatementCounter sql;

	private final JdbcTemplate jdbc;

	BatchReadsTest(@Autowired InterviewApi interviews, @Autowired SqlStatementCounter sql,
			@Autowired JdbcTemplate jdbc) {
		this.interviews = interviews;
		this.sql = sql;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void noSessions() {
		jdbc.update("DELETE FROM interview_session");   // cascades to topics, questions and answers
	}

	@Test
	void everyBatchReadCostsAsManyStatementsForEightQuestionsAsForThree() {
		UUID project = UUID.randomUUID();
		Map<String, Integer> three = statementsPerRead(project, sessionWith(project, 3));
		Map<String, Integer> eight = statementsPerRead(project, sessionWith(project, 8));

		assertThat(eight).isEqualTo(three);
		assertThat(eight.values()).allMatch(statements -> statements <= 2);   // answers also reads their questions
	}

	/** Each read the GraphQL resolvers make for one session query, with all of the session's ids. */
	private Map<String, Integer> statementsPerRead(UUID project, UUID session) {
		List<QuestionDto> asked = interviews.askedQuestions(List.of(session));
		List<UUID> questionIds = asked.stream().map(QuestionDto::id).toList();
		assertThat(asked).isNotEmpty();
		Map<String, Integer> statements = new LinkedHashMap<>();
		statements.put("sessions", count(() -> interviews.sessions(List.of(session))));
		statements.put("sessionsByProject", count(() -> interviews.sessionsByProject(List.of(project))));
		statements.put("askedQuestions", count(() -> interviews.askedQuestions(List.of(session))));
		statements.put("questions", count(() -> interviews.questions(questionIds)));
		statements.put("answers", count(() -> interviews.answers(questionIds)));
		return statements;
	}

	private int count(Supplier<List<?>> read) {
		sql.start();
		assertThat(read.get()).isNotEmpty();
		return sql.stop().size();
	}

	/**
	 * A session written straight to the database: {@code count} asked questions, question 2 a follow-up on question
	 * 1, every question but the last answered and graded, each citing OrderService#refund (lines 27-42).
	 */
	private UUID sessionWith(UUID project, int count) {
		UUID session = jdbc.queryForObject("""
				INSERT INTO interview_session (user_id, project_id, status, difficulty, planned_question_count)
				VALUES ('user-a', ?, 'IN_PROGRESS', 'JUNIOR', ?) RETURNING id
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
