package com.aryankanani.repointerviewer.project;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Analyzes one project start to finish in this JVM (LLD §6.3), for {@link InProcessAnalysisPipeline}:
 * SUBMITTED → CLONING → PARSING → EMBEDDING → SUMMARIZING → READY, or FAILED with a reason the user can read.
 * The stages themselves are {@link AnalysisStages}, shared with the Kafka pipeline (task 2.3), which runs each one in
 * its own consumer.
 */
@Component
class AnalysisJob {

	private final AnalysisStages stages;

	AnalysisJob(AnalysisStages stages) {
		this.stages = stages;
	}

	/**
	 * Runs the analysis if the project is still SUBMITTED; otherwise does nothing, so starting it twice is safe.
	 * Never throws: a failure is stored on the project.
	 */
	void run(UUID projectId) {
		MDC.put("projectId", projectId.toString());
		try {
			// Each stage returns false when it failed (FAILED is stored) or did not apply: then the rest do not run.
			if (stages.begin(projectId) && stages.ingest(projectId, AnalysisStages.NOTHING)
					&& stages.embed(projectId, AnalysisStages.NOTHING)) {
				stages.summarize(projectId, AnalysisStages.NOTHING);
			}
		}
		finally {
			MDC.remove("projectId");
		}
	}

}
