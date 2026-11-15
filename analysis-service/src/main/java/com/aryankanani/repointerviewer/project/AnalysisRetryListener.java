package com.aryankanani.repointerviewer.project;

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
 * Hears from Kafka's error handler (task 2.4, {@code common.messaging.KafkaErrorHandlingConfig}). When an analysis
 * event was sent to its dead-letter topic, the project it belongs to (the record key) is marked FAILED, so the user
 * sees why and can call {@code reanalyzeProject}. Runs on the consumer thread, before the offset is committed.
 */
@Component
@Profile("kafka")
class AnalysisRetryListener implements RetryListener {

	private static final Logger log = LoggerFactory.getLogger(AnalysisRetryListener.class);

	private static final Set<String> ANALYSIS_TOPICS = Set.of(Topics.ANALYSIS_REQUESTED, Topics.CHUNKS_READY,
			Topics.EMBEDDED);

	private final AnalysisStages stages;

	AnalysisRetryListener(AnalysisStages stages) {
		this.stages = stages;
	}

	@Override
	public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
		if (ANALYSIS_TOPICS.contains(record.topic())) {
			log.info("Delivery {} of {}-{}@{} failed: {}", deliveryAttempt, record.topic(), record.partition(),
					record.offset(), rootCause(ex).getClass().getSimpleName());
		}
	}

	@Override
	public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
		if (!ANALYSIS_TOPICS.contains(record.topic())) {
			return;
		}
		UUID projectId;
		try {
			projectId = UUID.fromString(String.valueOf(record.key()));
		}
		catch (IllegalArgumentException notAProjectId) {
			log.warn("Dead-lettered {}-{}@{} has no project id as key", record.topic(), record.partition(),
					record.offset());
			return;
		}
		MDC.put("projectId", projectId.toString());
		try {
			stages.failDeadLettered(projectId, ex);
		}
		finally {
			MDC.remove("projectId");
		}
	}

	private static Throwable rootCause(Throwable ex) {
		Throwable cause = ex;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

}
