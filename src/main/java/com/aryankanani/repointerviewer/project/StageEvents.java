package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads a stage's Kafka message, and keeps only the event type that stage handles, at a version it knows. */
@Component
@Profile("kafka")
class StageEvents {

	private static final Logger log = LoggerFactory.getLogger(StageEvents.class);

	private static final int KNOWN_VERSION = 1;

	private final JsonMapper json;

	StageEvents(JsonMapper json) {
		this.json = json;
	}

	/** @return empty for another type or an unknown version: a consumer must not guess what it means */
	Optional<EventEnvelope> read(String message, String expectedType) {
		EventEnvelope event = json.readValue(message, EventEnvelope.class);
		if (!expectedType.equals(event.type()) || event.version() != KNOWN_VERSION) {
			log.warn("Ignored event {}: type {} version {}, expected {} version {}", event.eventId(), event.type(),
					event.version(), expectedType, KNOWN_VERSION);
			return Optional.empty();
		}
		return Optional.of(event);
	}

}
