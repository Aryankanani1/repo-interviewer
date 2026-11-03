package com.aryankanani.repointerviewer.common.messaging;

import java.util.List;

/**
 * The Kafka topics of Phase 2 (LLD §13.3). Here in {@code common} because {@code project}, {@code interview} and
 * {@code api} all produce or consume them. Every event is keyed by its aggregate id (project or session), so one
 * project's or one session's events land on one partition and stay in order.
 */
public final class Topics {

	/** Key projectId. API (outbox) → ingestion worker. */
	public static final String ANALYSIS_REQUESTED = "project.analysis.requested";

	/** Key projectId. Ingestion worker → embedding worker. */
	public static final String CHUNKS_READY = "project.chunks.ready";

	/** Key projectId. Embedding worker → summary worker. */
	public static final String EMBEDDED = "project.embedded";

	/** Key projectId. Every stage → each API instance (subscription fan-out). */
	public static final String ANALYSIS_PROGRESS = "project.analysis.progress";

	/** Key sessionId. API (outbox) → evaluation worker. */
	public static final String ANSWER_SUBMITTED = "interview.answer.submitted";

	/** Key sessionId. Evaluation worker → interview orchestrator. */
	public static final String ANSWER_EVALUATED = "interview.answer.evaluated";

	/** Key sessionId. Orchestrator → each API instance (subscription fan-out). */
	public static final String QUESTION_READY = "interview.question.ready";

	public static final List<String> ALL = List.of(ANALYSIS_REQUESTED, CHUNKS_READY, EMBEDDED, ANALYSIS_PROGRESS,
			ANSWER_SUBMITTED, ANSWER_EVALUATED, QUESTION_READY);

	private Topics() {
	}

	/**
	 * The dead-letter topic of {@code topic} (task 2.4): where the error handler puts an event it gave up on, same
	 * key and partition. The suffix is Spring Kafka 4's default, {@code -dlt}.
	 */
	public static String dlt(String topic) {
		return topic + "-dlt";
	}

}
