package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On startup, fails analyses that were cut off: a project left in CLONING, PARSING, EMBEDDING or SUMMARIZING
 * because the app stopped mid-job would otherwise look busy forever (LLD §6.10).
 *
 * <p>Only rows untouched for {@code app.analysis.stale-after} (15 min), not every running one: with several app
 * instances (Phase 3), another instance may still be working on it.
 */
@Component
class StaleAnalysisRecovery {

	static final String REASON = "Analysis was interrupted. Use reanalyzeProject to retry.";

	private static final Logger log = LoggerFactory.getLogger(StaleAnalysisRecovery.class);

	private final ProjectRepository projects;

	private final Duration staleAfter;

	private final TransactionTemplate transaction;

	StaleAnalysisRecovery(ProjectRepository projects, AppProperties properties,
			PlatformTransactionManager transactionManager) {
		this.projects = projects;
		this.staleAfter = properties.analysis().staleAfter();
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** Returns nothing on purpose: Spring would publish a listener's return value as a new event. */
	@EventListener(ApplicationReadyEvent.class)
	void onApplicationReady() {
		recover();
	}

	/** @return how many projects were marked FAILED */
	int recover() {
		Integer failed = transaction.execute(status -> projects.failStale(Instant.now().minus(staleAfter), REASON));
		if (failed > 0) {
			log.warn("Marked {} interrupted analyses as FAILED", failed);
		}
		return failed;
	}

}
