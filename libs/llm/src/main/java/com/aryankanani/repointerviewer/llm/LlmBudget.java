package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Caps on model calls, checked in code before every call (DECISIONS.md D-007), so a bug or a loop cannot run up
 * the bill.
 * <ul>
 * <li><b>Per day</b> ({@code app.llm.max-calls-per-day}): counts today's {@code llm_call} rows (UTC day). A soft
 * cap: two calls checking at the same moment can both pass, so the day can end a few calls over.</li>
 * <li><b>Per interview</b> ({@code app.llm.max-calls-per-session}): one atomic UPDATE that only adds 1 while the
 * count is below the limit. Postgres runs it row by row under a lock, so two concurrent calls can never both
 * take the last slot. An exact cap.</li>
 * </ul>
 * A call that fails still used its slot: a failed call can cost tokens too.
 */
@Component
class LlmBudget {

	private final LlmCallRepository calls;

	private final JdbcTemplate jdbc;

	private final int maxCallsPerSession;

	private final int maxCallsPerDay;

	@Autowired
	LlmBudget(LlmCallRepository calls, JdbcTemplate jdbc, AppProperties properties) {
		this(calls, jdbc, properties.llm().maxCallsPerSession(), properties.llm().maxCallsPerDay());
	}

	LlmBudget(LlmCallRepository calls, JdbcTemplate jdbc, int maxCallsPerSession, int maxCallsPerDay) {
		this.calls = calls;
		this.jdbc = jdbc;
		this.maxCallsPerSession = maxCallsPerSession;
		this.maxCallsPerDay = maxCallsPerDay;
	}

	/**
	 * Takes one call from the budget, or throws.
	 *
	 * @param sessionId null for calls outside an interview (the repository summary): only the daily cap applies
	 * @throws BudgetExceededException a cap is reached; nothing was taken
	 */
	void reserve(UUID sessionId) {
		Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);   // midnight UTC
		if (calls.countByCreatedAtGreaterThanEqual(startOfDay) >= maxCallsPerDay) {
			throw new BudgetExceededException(
					"The daily limit of " + maxCallsPerDay + " AI calls is used up. Try again tomorrow.");
		}
		if (sessionId != null) {
			int updated = jdbc.update("""
					UPDATE interview_session SET llm_call_count = llm_call_count + 1
					WHERE id = ? AND llm_call_count < ?
					""", sessionId, maxCallsPerSession);
			if (updated == 0) {
				throw new BudgetExceededException(
						"This interview used all of its " + maxCallsPerSession + " AI calls.");
			}
		}
	}

}
