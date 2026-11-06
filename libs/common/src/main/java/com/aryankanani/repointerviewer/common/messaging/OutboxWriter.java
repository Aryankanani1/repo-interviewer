package com.aryankanani.repointerviewer.common.messaging;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event to the outbox (task 2.2, LLD §13.1), in the transaction that changes the state it describes: both
 * commit or neither does, so there is no "saved but not sent" and no "sent but rolled back" (the dual-write problem).
 * {@link OutboxRelay} sends it to Kafka later.
 *
 * <p>Plain database work, so it exists without the {@code kafka} profile too; only the relay needs a broker.
 */
@Component
public class OutboxWriter {

	private final OutboxEventRepository events;

	private final JsonMapper json;

	OutboxWriter(OutboxEventRepository events, JsonMapper json) {
		this.events = events;
		this.json = json;
	}

	/**
	 * {@code MANDATORY}: joins the caller's transaction and refuses to run without one
	 * ({@code IllegalTransactionStateException}), so an event can never be saved apart from its state change.
	 *
	 * @param topic one of {@link Topics#ALL}
	 * @param aggregateId the Kafka key
	 * @param payload any value Jackson can write; sent inside the {@link EventEnvelope}
	 * @return the event id, which is also the envelope's {@code eventId}
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID write(String topic, AggregateType aggregateType, UUID aggregateId, String type, Object payload) {
		if (!Topics.ALL.contains(topic)) {
			throw new IllegalArgumentException("Unknown topic " + topic + ": declare it in Topics");
		}
		return events.save(new OutboxEvent(topic, aggregateType, aggregateId, type, json.writeValueAsString(payload)))
				.getId();
	}

}
