package com.aryankanani.repointerviewer.common.error;

/**
 * Another service answered 503 LLM_UNAVAILABLE: a model behind it could not be reached (task 4.1). Rebuilt from the
 * problem detail by {@code DomainErrorHandler}, with that service's message, so a caller treats it exactly like a
 * model of its own being down: a Kafka consumer retries the event with backoff (task 3.6).
 */
public class RemoteModelUnavailableException extends ModelUnavailableException {

	public RemoteModelUnavailableException(String message) {
		super(message, null);
	}

}
