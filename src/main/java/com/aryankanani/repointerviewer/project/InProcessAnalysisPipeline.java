package com.aryankanani.repointerviewer.project;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 1 {@link AnalysisPipeline} (LLD §6.1): runs {@link AnalysisJob} in this JVM, one virtual thread per
 * project. A virtual thread is cheap (no OS thread is held while it waits on the network or the database), so
 * one per analysis is fine.
 *
 * <p>The map of running jobs stops a second start of the same project while the first is still running. Across
 * several JVMs the job's own check (only SUBMITTED projects start, with optimistic locking) does the same.
 */
@Component
class InProcessAnalysisPipeline implements AnalysisPipeline {

	private static final Logger log = LoggerFactory.getLogger(InProcessAnalysisPipeline.class);

	private final AnalysisJob job;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<UUID, Future<?>> running = new ConcurrentHashMap<>();

	InProcessAnalysisPipeline(AnalysisJob job) {
		this.job = job;
	}

	/** Returns at once. A start while the same project is already running is ignored. */
	@Override
	public void start(UUID projectId) {
		// computeIfAbsent is atomic per key: of two concurrent starts, only one submits a job. The job's own
		// remove(...) waits until computeIfAbsent has stored the Future, so it cannot run too early.
		running.computeIfAbsent(projectId, id -> executor.submit(() -> {
			try {
				job.run(id);
			}
			catch (RuntimeException ex) {
				log.error("Analysis job for project {} crashed", id, ex);   // run() catches everything; a bug
			}
			finally {
				running.remove(id);
			}
		}));
	}

	boolean isRunning(UUID projectId) {
		return running.containsKey(projectId);
	}

	/**
	 * On shutdown, interrupts running jobs instead of waiting for them (an analysis can take minutes). A job cut
	 * off mid-stage is marked FAILED by {@link StaleAnalysisRecovery} on a later start.
	 */
	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

}
