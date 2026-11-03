package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;

/**
 * Hears from Kafka's error handler (task 2.5, like {@code project.AnalysisRetryListener}). When a worker's event was
 * sent to its dead-letter topic, for example after the model stayed unavailable through every retry, the session in
 * the record key is marked FAILED and SessionFailed is written, so the candidate is not left waiting.
 */
@Component
@Profile("kafka")
class InterviewRetryListener implements RetryListener {

	static final String GAVE_UP = "The interview could not continue. Start a new interview.";

	private static final Logger log = LoggerFactory.getLogger(InterviewRetryListener.class);

	/** Read by the workers. {@link InterviewEventsFanOut} also reads answer.evaluated, but it never throws. */
	private static final Set<String> WORKER_TOPICS = Set.of(Topics.ANSWER_SUBMITTED, Topics.ANSWER_EVALUATED);

	private final AnswerFlowOutbox outcomes;

	InterviewRetryListener(AnswerFlowOutbox outcomes) {
		this.outcomes = outcomes;
	}

	@Override
	public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
		if (WORKER_TOPICS.contains(record.topic())) {
			log.info("Delivery {} of {}-{}@{} failed", deliveryAttempt, record.topic(), record.partition(),
					record.offset());
		}
	}

	@Override
	public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
		if (!WORKER_TOPICS.contains(record.topic())) {
			return;
		}
		UUID sessionId;
		try {
			sessionId = UUID.fromString(String.valueOf(record.key()));
		}
		catch (IllegalArgumentException notASessionId) {
			log.warn("Dead-lettered {}-{}@{} has no session id as key", record.topic(), record.partition(),
					record.offset());
			return;
		}
		MDC.put("sessionId", sessionId.toString());
		try {
			log.warn("Event dead-lettered, session FAILED");
			outcomes.failed(null, null, sessionId, null, null, reason(ex));
		}
		catch (RuntimeException secondary) {
			log.error("Could not mark the session FAILED", secondary);
		}
		finally {
			MDC.remove("sessionId");
		}
	}

	/** The first message we wrote for users in the causes (for example the model is unavailable), else ours. */
	private static String reason(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof DomainException domain) {
				return domain.getMessage();
			}
		}
		return GAVE_UP;
	}

}
