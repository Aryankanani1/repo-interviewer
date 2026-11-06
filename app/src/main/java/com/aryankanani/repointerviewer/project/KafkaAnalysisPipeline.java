package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.messaging.AggregateType;
import com.aryankanani.repointerviewer.common.messaging.OutboxWriter;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2 {@link AnalysisPipeline} (task 2.3, LLD §13.3): analysis as three Kafka consumers, one per stage
 * ({@link IngestConsumer}, {@link EmbedConsumer}, {@link SummaryConsumer}), linked by events through the outbox:
 *
 * <pre>
 * submit/reanalyze tx: project + AnalysisRequested → project.analysis.requested → ingest
 *   ingest's last tx:  chunks    + ChunksReady       → project.chunks.ready       → embed
 *   embed's last tx:   vectors   + Embedded          → project.embedded           → summary → READY
 * </pre>
 *
 * Each consumer marks its event in {@code processed_event} in that same last transaction, so a redelivered event
 * changes nothing. The event payload is only the project id; everything a stage needs is in the database.
 */
@Component
@Profile("kafka")
class KafkaAnalysisPipeline implements AnalysisPipeline {

	static final String ANALYSIS_REQUESTED = "AnalysisRequested";

	static final String CHUNKS_READY = "ChunksReady";

	static final String EMBEDDED = "Embedded";

	/** The payload of the three analysis events. */
	record ProjectEvent(UUID projectId) {
	}

	private final OutboxWriter outbox;

	KafkaAnalysisPipeline(OutboxWriter outbox) {
		this.outbox = outbox;
	}

	/** Must run inside the transaction that made the project SUBMITTED: the event commits with it, or not at all. */
	@Override
	public void start(UUID projectId) {
		outbox.write(Topics.ANALYSIS_REQUESTED, AggregateType.PROJECT, projectId, ANALYSIS_REQUESTED,
				new ProjectEvent(projectId));
	}

	/**
	 * {@code BEFORE_COMMIT}: still inside {@code submit}'s or {@code reanalyze}'s transaction, and only when it is
	 * about to commit. A transaction that rolls back never gets here, and nothing is written.
	 */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onProjectSubmitted(ProjectSubmittedEvent event) {
		start(event.projectId());
	}

}
