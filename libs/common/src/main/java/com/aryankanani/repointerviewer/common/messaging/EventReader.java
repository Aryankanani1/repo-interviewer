package com.aryankanani.repointerviewer.common.messaging;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a consumer's Kafka message, and accepts only the event types that consumer handles, at a version it knows
 * (tasks 2.3 and 2.4; shared by {@code project} and {@code interview} since task 2.5).
 */
@Component
@Profile("kafka")
public class EventReader {

	private static final int KNOWN_VERSION = 1;

	private final JsonMapper json;

	EventReader(JsonMapper json) {
		this.json = json;
	}

	/**
	 * @throws InvalidEventException for anything else: a consumer must not guess what it means, and the error handler
	 *         sends it straight to the dead-letter topic (task 2.4)
	 */
	public EventEnvelope read(String message, String... expectedTypes) {
		if (message == null) {
			throw new InvalidEventException("Message without a value");
		}
		EventEnvelope event;
		try {
			event = json.readValue(message, EventEnvelope.class);
		}
		catch (JacksonException ex) {
			throw new InvalidEventException("Not an event envelope", ex);
		}
		if (event.eventId() == null || event.aggregateId() == null) {
			throw new InvalidEventException("Event without eventId or aggregateId");
		}
		if (!List.of(expectedTypes).contains(event.type()) || event.version() != KNOWN_VERSION) {
			throw new InvalidEventException("Event " + event.eventId() + ": type " + event.type() + " version "
					+ event.version() + ", expected " + String.join(" or ", expectedTypes) + " version "
					+ KNOWN_VERSION);
		}
		return event;
	}

	/** The event's payload as {@code type}. @throws InvalidEventException when it does not fit */
	public <T> T payload(EventEnvelope event, Class<T> type) {
		try {
			return json.treeToValue(event.payload(), type);
		}
		catch (JacksonException | IllegalArgumentException ex) {
			throw new InvalidEventException("Event " + event.eventId() + ": payload is not a " + type.getSimpleName(),
					ex);
		}
	}

}
