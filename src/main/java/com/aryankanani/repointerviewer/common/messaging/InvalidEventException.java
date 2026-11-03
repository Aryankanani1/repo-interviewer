package com.aryankanani.repointerviewer.common.messaging;

/**
 * A Kafka message a consumer cannot read: not an {@link EventEnvelope}, another event type, or a version it does not
 * know (task 2.4). Delivering it again would fail the same way, so it goes straight to the dead-letter topic, where
 * it is kept for inspection instead of being dropped.
 */
public class InvalidEventException extends RuntimeException {

	public InvalidEventException(String message) {
		super(message);
	}

	public InvalidEventException(String message, Throwable cause) {
		super(message, cause);
	}

}
