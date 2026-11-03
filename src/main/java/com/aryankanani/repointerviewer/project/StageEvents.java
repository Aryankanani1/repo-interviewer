package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import com.aryankanani.repointerviewer.common.messaging.InvalidEventException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Reads a stage's Kafka message, and accepts only the event type that stage handles, at a version it knows. */
@Component
@Profile("kafka")
class StageEvents {

	private static final int KNOWN_VERSION = 1;

	private final JsonMapper json;

	StageEvents(JsonMapper json) {
		this.json = json;
	}

	/**
	 * @throws InvalidEventException for anything else: a consumer must not guess what it means, and the error handler
	 *         sends it straight to the dead-letter topic (task 2.4)
	 */
	EventEnvelope read(String message, String expectedType) {
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
		if (!expectedType.equals(event.type()) || event.version() != KNOWN_VERSION) {
			throw new InvalidEventException("Event " + event.eventId() + ": type " + event.type() + " version "
					+ event.version() + ", expected " + expectedType + " version " + KNOWN_VERSION);
		}
		return event;
	}

}
