package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.messaging.ProcessedEvents;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stage 3 on Kafka (task 2.3): Embedded → the SUMMARY call → READY. Safe to run again after a crash: a project left
 * in SUMMARIZING is summarized again (one more model call, the only cost).
 */
@Component
@Profile("kafka")
class SummaryConsumer {

	static final String NAME = "analysis-summary";

	private final StageEvents events;

	private final AnalysisStages stages;

	private final ProcessedEvents processed;

	SummaryConsumer(StageEvents events, AnalysisStages stages, ProcessedEvents processed) {
		this.events = events;
		this.stages = stages;
		this.processed = processed;
	}

	@KafkaListener(topics = Topics.EMBEDDED, groupId = NAME)
	void onEmbedded(String message) {
		events.read(message, KafkaAnalysisPipeline.EMBEDDED).ifPresent(event -> {
			UUID projectId = event.aggregateId();
			MDC.put("projectId", projectId.toString());
			try {
				if (processed.isProcessed(event.eventId(), NAME)) {
					return;
				}
				stages.summarize(projectId, project -> processed.claim(event.eventId(), NAME));
			}
			finally {
				MDC.remove("projectId");
			}
		});
	}

}
