package com.aryankanani.repointerviewer.common.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumers (task 2.3, LLD §13.1): table {@code processed_event}, one row per (event, consumer).
 *
 * <p>Plain SQL, not JPA, on purpose: {@code INSERT ... ON CONFLICT DO NOTHING} reports a duplicate as "0 rows" and
 * the transaction goes on. With JPA a duplicate key is an exception that marks the whole transaction rollback-only.
 */
@Component
public class ProcessedEvents {

	private final JdbcTemplate jdbc;

	ProcessedEvents(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A quick look before the slow work, so a plain redelivery is skipped at once. {@link #claim} decides. */
	public boolean isProcessed(UUID eventId, String consumer) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM processed_event WHERE event_id = ? AND consumer = ?)", Boolean.class,
				eventId, consumer));
	}

	/**
	 * Marks the event processed, in the consumer's transaction ({@code MANDATORY}), so the mark commits with the state
	 * change. If another delivery of the same event is still running, this waits for it, then sees its row.
	 *
	 * @throws EventAlreadyProcessed when this consumer already handled it; the transaction should roll back
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void claim(UUID eventId, String consumer) {
		int inserted = jdbc.update("INSERT INTO processed_event (event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING",
				eventId, consumer);
		if (inserted == 0) {
			throw new EventAlreadyProcessed(eventId, consumer);
		}
	}

}
