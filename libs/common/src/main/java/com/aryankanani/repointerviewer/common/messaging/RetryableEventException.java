package com.aryankanani.repointerviewer.common.messaging;

/**
 * Thrown by a Kafka consumer whose event may work if delivered again later, for example while the model is down
 * (task 2.4). The only exception {@link KafkaErrorHandlingConfig} retries: the consumer decides what is worth a retry,
 * because only it knows what failed. Everything else goes straight to the dead-letter topic.
 */
public class RetryableEventException extends RuntimeException {

	public RetryableEventException(Throwable cause) {
		super(cause.getMessage(), cause);
	}

}
