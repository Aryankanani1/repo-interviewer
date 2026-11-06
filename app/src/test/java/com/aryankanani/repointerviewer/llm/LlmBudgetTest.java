package com.aryankanani.repointerviewer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.8 acceptance tests for {@link LlmBudget}: 3 calls per session, 5 per day. */
@IntegrationTest
class LlmBudgetTest {

	private static final int PER_SESSION = 3;

	private static final int PER_DAY = 5;

	private final JdbcTemplate jdbc;

	private final FixtureIndexer fixture;

	private final LlmBudget budget;

	LlmBudgetTest(@Autowired LlmCallRepository calls, @Autowired JdbcTemplate jdbc, @Autowired FixtureIndexer fixture) {
		this.jdbc = jdbc;
		this.fixture = fixture;
		this.budget = new LlmBudget(calls, jdbc, PER_SESSION, PER_DAY);
	}

	@BeforeEach
	void deleteState() {
		jdbc.update("DELETE FROM llm_call");
		jdbc.update("DELETE FROM project");   // cascades to interview_session
	}

	@Test
	void aSessionGetsExactlyItsLimit() {
		UUID session = newSession();

		for (int i = 0; i < PER_SESSION; i++) {
			budget.reserve(session);
		}

		assertThatThrownBy(() -> budget.reserve(session)).isInstanceOf(BudgetExceededException.class)
				.hasMessageContaining("used all of its 3 AI calls")
				.satisfies(ex -> assertThat(((BudgetExceededException) ex).code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED));
		assertThat(callCount(session)).isEqualTo(PER_SESSION);
	}

	@Test
	void sessionsHaveSeparateBudgets() {
		UUID full = newSession();
		UUID fresh = newSession();
		for (int i = 0; i < PER_SESSION; i++) {
			budget.reserve(full);
		}

		budget.reserve(fresh);

		assertThat(callCount(fresh)).isEqualTo(1);
	}

	/** The atomic UPDATE: 10 threads race for 3 slots, and exactly 3 win. */
	@Test
	void concurrentCallsCannotOvershootTheSessionLimit() throws Exception {
		UUID session = newSession();
		List<Callable<Boolean>> racers = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			racers.add(() -> {
				try {
					budget.reserve(session);
					return true;
				}
				catch (BudgetExceededException ex) {
					return false;
				}
			});
		}

		int won = 0;
		try (ExecutorService threads = Executors.newFixedThreadPool(10)) {
			for (Future<Boolean> result : threads.invokeAll(racers)) {
				won += result.get() ? 1 : 0;
			}
		}

		assertThat(won).isEqualTo(PER_SESSION);
		assertThat(callCount(session)).isEqualTo(PER_SESSION);
	}

	@Test
	void theDailyLimitCountsTodaysCalls() {
		insertCalls(PER_DAY, "now()");

		assertThatThrownBy(() -> budget.reserve(null)).isInstanceOf(BudgetExceededException.class)
				.hasMessageContaining("daily limit of 5 AI calls");
	}

	@Test
	void yesterdaysCallsDoNotCount() {
		insertCalls(PER_DAY, "now() - interval '1 day'");

		budget.reserve(null);
	}

	@Test
	void theDailyLimitIsCheckedBeforeASessionSlotIsTaken() {
		UUID session = newSession();
		insertCalls(PER_DAY, "now()");

		assertThatThrownBy(() -> budget.reserve(session)).isInstanceOf(BudgetExceededException.class);
		assertThat(callCount(session)).isZero();
	}

	private UUID newSession() {
		return jdbc.queryForObject("""
				INSERT INTO interview_session (project_id, status, difficulty, planned_question_count)
				VALUES (?, 'IN_PROGRESS', 'JUNIOR', 5)
				RETURNING id
				""", UUID.class, fixture.newProject());
	}

	private int callCount(UUID session) {
		return jdbc.queryForObject("SELECT llm_call_count FROM interview_session WHERE id = ?", Integer.class, session);
	}

	private void insertCalls(int count, String createdAt) {
		for (int i = 0; i < count; i++) {
			jdbc.update("INSERT INTO llm_call (purpose, model, prompt_version, latency_ms, success, created_at) "
					+ "VALUES ('SUMMARY', 'claude-haiku-4-5', 'repo-summary@00000000', 1, true, " + createdAt + ")");
		}
	}

}
