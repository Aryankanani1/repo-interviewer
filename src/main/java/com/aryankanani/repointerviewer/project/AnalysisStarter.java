package com.aryankanani.repointerviewer.project;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts analysis only after the insert has committed. If the transaction rolls back, the event is
 * dropped and no job runs for a row that does not exist. An event published outside a transaction is
 * also dropped ({@code fallbackExecution = false}), which is what we want.
 */
@Component
class AnalysisStarter {

	private final AnalysisPipeline analysisPipeline;

	AnalysisStarter(AnalysisPipeline analysisPipeline) {
		this.analysisPipeline = analysisPipeline;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onProjectSubmitted(ProjectSubmittedEvent event) {
		analysisPipeline.start(event.projectId());
	}

}
