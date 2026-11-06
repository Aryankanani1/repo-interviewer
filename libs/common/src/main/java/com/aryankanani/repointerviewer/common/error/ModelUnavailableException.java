package com.aryankanani.repointerviewer.common.error;

/**
 * A model the app depends on could not be reached or answered with an error: the chat model (Claude) or the
 * embedding model (Ollama). Worth trying again later. The Kafka consumers turn it into a retryable event, so it is
 * delivered again with backoff (DECISIONS.md D-019, task 3.6); in one JVM the step fails with this message.
 */
public abstract class ModelUnavailableException extends DomainException {

	protected ModelUnavailableException(String message, Throwable cause) {
		super(ErrorCode.LLM_UNAVAILABLE, message);   // one code for both: a client tries again later either way
		initCause(cause);
	}

}
