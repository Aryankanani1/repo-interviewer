package com.aryankanani.repointerviewer.common.messaging;

import java.util.UUID;

/**
 * Thrown by {@link ProcessedEvents#claim} when this consumer already handled the event: a redelivery, which
 * at-least-once delivery allows. Thrown inside the consumer's transaction, so its state change rolls back too.
 */
public class EventAlreadyProcessed extends RuntimeException {

	public EventAlreadyProcessed(UUID eventId, String consumer) {
		super("event " + eventId + " was already processed by " + consumer);
	}

}
