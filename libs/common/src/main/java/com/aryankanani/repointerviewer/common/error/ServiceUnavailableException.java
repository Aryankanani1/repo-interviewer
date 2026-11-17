package com.aryankanani.repointerviewer.common.error;

/**
 * Another service of this system could not be reached or did not answer in time, or its circuit breaker is open
 * after too many such failures (task 4.2). Thrown by the caller's {@code ServiceCallGuard}; one hop further, it is
 * rebuilt from the 503 SERVICE_UNAVAILABLE problem detail. Worth trying again later: the Kafka consumers retry it with
 * backoff, like an unavailable model.
 */
public class ServiceUnavailableException extends DomainException {

	public ServiceUnavailableException(String message) {
		super(ErrorCode.SERVICE_UNAVAILABLE, message);
	}

	public ServiceUnavailableException(String message, Throwable cause) {
		this(message);
		initCause(cause);
	}

}
