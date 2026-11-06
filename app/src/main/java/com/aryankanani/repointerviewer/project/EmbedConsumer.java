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
 * Stage 2 on Kafka (task 2.3): ChunksReady → embed the chunks that have no vector yet → Embedded. Safe to run again
 * after a crash: chunks embedded before it are skipped, the rest get done.
 */
@Component
@Profile("kafka")
class EmbedConsumer {

	static final String NAME = "analysis-embed";

	private final EventReader events;

	private final AnalysisStages stages;

	private final ProcessedEvents processed;

	private final OutboxWriter outbox;

	EmbedConsumer(EventReader events, AnalysisStages stages, ProcessedEvents processed, OutboxWriter outbox) {
		this.events = events;
		this.stages = stages;
		this.processed = processed;
		this.outbox = outbox;
	}

	@KafkaListener(topics = Topics.CHUNKS_READY, groupId = NAME, autoStartup = "${app.roles.analysis-worker}")
	void onChunksReady(String message) {
		EventEnvelope event = events.read(message, KafkaAnalysisPipeline.CHUNKS_READY);
		UUID projectId = event.aggregateId();
		MDC.put("projectId", projectId.toString());
		try {
			if (processed.isProcessed(event.eventId(), NAME)) {
				return;
			}
			stages.embed(projectId, project -> {
				processed.claim(event.eventId(), NAME);
				outbox.write(Topics.EMBEDDED, AggregateType.PROJECT, projectId, KafkaAnalysisPipeline.EMBEDDED,
						new ProjectEvent(projectId));
			}, AnalysisStages.WhenModelUnavailable.RETRY);
		}
		finally {
			MDC.remove("projectId");
		}
	}

}
