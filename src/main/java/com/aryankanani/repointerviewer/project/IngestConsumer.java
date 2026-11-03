package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.messaging.AggregateType;
import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import com.aryankanani.repointerviewer.common.messaging.EventReader;
import com.aryankanani.repointerviewer.common.messaging.OutboxWriter;
import com.aryankanani.repointerviewer.common.messaging.ProcessedEvents;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.project.KafkaAnalysisPipeline.ProjectEvent;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stage 1 on Kafka (task 2.3): AnalysisRequested → begin and ingest (clone, parse, chunks) → ChunksReady. Starts only
 * from SUBMITTED, like the in-process job: a redelivery after a crash mid-ingest finds CLONING or PARSING, does
 * nothing, and StaleAnalysisRecovery fails the project later (LLD §13.4).
 */
@Component
@Profile("kafka")
class IngestConsumer {

	static final String NAME = "analysis-ingest";

	private final EventReader events;

	private final AnalysisStages stages;

	private final ProcessedEvents processed;

	private final OutboxWriter outbox;

	IngestConsumer(EventReader events, AnalysisStages stages, ProcessedEvents processed, OutboxWriter outbox) {
		this.events = events;
		this.stages = stages;
		this.processed = processed;
		this.outbox = outbox;
	}

	@KafkaListener(topics = Topics.ANALYSIS_REQUESTED, groupId = NAME)
	void onAnalysisRequested(String message) {
		EventEnvelope event = events.read(message, KafkaAnalysisPipeline.ANALYSIS_REQUESTED);
		UUID projectId = event.aggregateId();
		MDC.put("projectId", projectId.toString());
		try {
			if (processed.isProcessed(event.eventId(), NAME) || !stages.begin(projectId)) {
				return;
			}
			stages.ingest(projectId, project -> {
				processed.claim(event.eventId(), NAME);
				outbox.write(Topics.CHUNKS_READY, AggregateType.PROJECT, projectId, KafkaAnalysisPipeline.CHUNKS_READY,
						new ProjectEvent(projectId));
			});
		}
		finally {
			MDC.remove("projectId");
		}
	}

}
