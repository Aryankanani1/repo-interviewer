package com.aryankanani.repointerviewer.project;

import java.util.UUID;

/**
 * Port for the slow analysis work (clone, parse, embed, summarize). Phase 1: in-process on virtual
 * threads (task 1.9). Phase 2: Kafka, with the {@code kafka} profile (task 2.3). Callers do not change between the
 * two (DECISIONS.md D-001): {@link ProjectService} only publishes a {@link ProjectSubmittedEvent} in its transaction.
 * The in-process pipeline starts after that commits ({@link AnalysisStarter}); the Kafka pipeline writes its outbox
 * event before it commits, so the event commits with the project row.
 */
public interface AnalysisPipeline {

	/** Starts analysis asynchronously. Must return quickly. Safe to call twice for the same project. */
	void start(UUID projectId);

}
