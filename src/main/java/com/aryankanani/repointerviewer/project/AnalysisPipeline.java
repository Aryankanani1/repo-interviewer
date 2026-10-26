package com.aryankanani.repointerviewer.project;

import java.util.UUID;

/**
 * Port for the slow analysis work (clone, parse, embed, summarize). Phase 1: in-process on virtual
 * threads (task 1.9). Phase 2: Kafka. Callers do not change between the two (DECISIONS.md D-001).
 */
public interface AnalysisPipeline {

	/** Starts analysis asynchronously. Must return quickly. Safe to call twice for the same project. */
	void start(UUID projectId);

}
