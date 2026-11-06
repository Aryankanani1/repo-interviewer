package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.codebase.CodebaseIndexer;
import com.aryankanani.repointerviewer.codebase.CodebaseQueries;
import com.aryankanani.repointerviewer.codebase.IndexResult;
import com.aryankanani.repointerviewer.codebase.embed.EmbeddingService;
import com.aryankanani.repointerviewer.codebase.fetch.ClonedRepo;
import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.codebase.fetch.RepoFetcher;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ModelUnavailableException;
import com.aryankanani.repointerviewer.common.messaging.EventAlreadyProcessed;
import com.aryankanani.repointerviewer.common.messaging.RetryableEventException;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.RepoSummary;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The analysis stages (LLD §6.3), written once for both pipelines: {@link AnalysisJob} runs them one after another in
 * this JVM; the Kafka consumers (task 2.3) run one each, triggered by the previous stage's event.
 *
 * <pre>
 * begin      SUBMITTED → CLONING
 * ingest     CLONING → PARSING → EMBEDDING   (clone, parse, chunks; the clone is deleted at the end)
 * embed      EMBEDDING                        (vectors for the chunks that have none)
 * summarize  EMBEDDING → SUMMARIZING → READY  (the SUMMARY call)
 * </pre>
 *
 * <p>Each stage runs its slow work outside any transaction, stores the result in short transactions, and never
 * throws: a failure marks the project FAILED with a reason the user can read. The one exception is an unavailable
 * model, chat or embedding, on Kafka ({@link WhenModelUnavailable#RETRY}, tasks 2.4 and 3.6). A stage that finds the
 * project in another status does nothing, so a repeated trigger is harmless.
 *
 * <p>{@code inLastTransaction} runs first inside the stage's last transaction. The Kafka consumers claim the event
 * there and write the next one, so the stage's result, its next event and the "processed" mark commit together or
 * not at all. When the claim finds the event already handled ({@link EventAlreadyProcessed}), everything rolls back
 * and the stage ends quietly. {@link AnalysisJob} passes {@link #NOTHING}.
 */
@Component
class AnalysisStages {

	static final String BRANCH_MOVED = "The repository changed since you submitted it. Submit it again to analyze "
			+ "the latest commit.";

	static final Consumer<Project> NOTHING = project -> {
	};

	/** What {@link #embed} and {@link #summarize} do when a model, embedding or chat, cannot be reached (2.4, 3.6). */
	enum WhenModelUnavailable {
		/** Store FAILED, as for any other error: nothing would try again ({@link AnalysisJob}). */
		FAIL,
		/** Throw {@link RetryableEventException}, so Kafka's error handler delivers the event again later. */
		RETRY
	}

	private static final Logger log = LoggerFactory.getLogger(AnalysisStages.class);

	private static final Set<AnalysisStatus> BEFORE_SUMMARY = Set.of(AnalysisStatus.EMBEDDING, AnalysisStatus.SUMMARIZING);

	private static final Set<AnalysisStatus> RUNNING = Set.of(AnalysisStatus.CLONING, AnalysisStatus.PARSING,
			AnalysisStatus.EMBEDDING, AnalysisStatus.SUMMARIZING);

	private final ProjectRepository projects;

	private final TransactionTemplate transaction;

	private final RepoFetcher repoFetcher;

	private final CodebaseIndexer indexer;

	private final EmbeddingService embeddingService;

	private final CodebaseQueries codebaseQueries;

	private final LlmGateway llm;

	private final SummaryValidator summaryValidator;

	private final Path workDir;

	AnalysisStages(ProjectRepository projects, PlatformTransactionManager transactionManager, RepoFetcher repoFetcher,
			CodebaseIndexer indexer, EmbeddingService embeddingService, CodebaseQueries codebaseQueries,
			LlmGateway llm, SummaryValidator summaryValidator, AppProperties properties) {
		this.projects = projects;
		this.transaction = new TransactionTemplate(transactionManager);
		this.repoFetcher = repoFetcher;
		this.indexer = indexer;
		this.embeddingService = embeddingService;
		this.codebaseQueries = codebaseQueries;
		this.llm = llm;
		this.summaryValidator = summaryValidator;
		this.workDir = properties.repo().workDir();
	}

	/** SUBMITTED → CLONING in one transaction. @return false when the project is missing, not SUBMITTED, or taken */
	boolean begin(UUID projectId) {
		try {
			return Boolean.TRUE.equals(transaction.execute(status -> {
				Project project = projects.findById(projectId).orElse(null);
				if (project == null || project.getStatus() != AnalysisStatus.SUBMITTED) {
					log.info("Analysis not started: project is {}", project == null ? "missing" : project.getStatus());
					return false;
				}
				project.startAnalysis();
				return true;
			}));
		}
		catch (OptimisticLockingFailureException ex) {
			// Another job changed the row between our read and our write, and owns the analysis now.
			log.info("Analysis not started: another job started it first");
			return false;
		}
	}

	/** CLONING → PARSING → EMBEDDING. @return true when the chunks are stored */
	boolean ingest(UUID projectId, Consumer<Project> inLastTransaction) {
		Project project = projects.findById(projectId).orElse(null);
		if (project == null || project.getStatus() != AnalysisStatus.CLONING) {
			return skipped("ingest", project);
		}
		AnalysisStatus stage = AnalysisStatus.CLONING;
		try {
			IndexResult index;
			try (ClonedRepo clone = clone(project)) {
				stage = AnalysisStatus.PARSING;
				update(projectId, NOTHING, p -> p.transitionTo(AnalysisStatus.PARSING));
				index = indexer.index(projectId, clone.directory());
				update(projectId, inLastTransaction, p -> p.recordIndex(index.fileCount(), index.javaFileCount(),
						index.totalLines(), index.chunkCount(), index.primaryLanguage(), index.repoMap(),
						index.buildFiles(), index.readme()));
			}
			log.info("Parsed {}", index);
			return true;
		}
		catch (EventAlreadyProcessed ex) {
			return duplicate(ex);
		}
		catch (RuntimeException ex) {
			fail(projectId, stage, ex);
			return false;
		}
	}

	/**
	 * Embeds the chunks that have no vector yet; the project stays EMBEDDING. @return true when all are embedded
	 * @throws RetryableEventException only with {@link WhenModelUnavailable#RETRY}, when the embedding model is
	 *         unavailable; the chunks embedded so far stay saved, and the next delivery does the rest
	 */
	boolean embed(UUID projectId, Consumer<Project> inLastTransaction, WhenModelUnavailable whenUnavailable) {
		Project project = projects.findById(projectId).orElse(null);
		if (project == null || project.getStatus() != AnalysisStatus.EMBEDDING) {
			return skipped("embed", project);
		}
		try {
			log.info("Embedded {} chunks", embeddingService.embedPending(projectId));
			update(projectId, inLastTransaction, p -> {
				if (p.getStatus() != AnalysisStatus.EMBEDDING) {
					throw new IllegalStateException("Project " + projectId + " left EMBEDDING while embedding");
				}
			});
			return true;
		}
		catch (EventAlreadyProcessed ex) {
			return duplicate(ex);
		}
		catch (ModelUnavailableException ex) {
			if (whenUnavailable == WhenModelUnavailable.RETRY) {
				log.warn("Chunks not embedded: model unavailable; the error handler retries or dead-letters it");
				throw new RetryableEventException(ex);
			}
			fail(projectId, AnalysisStatus.EMBEDDING, ex);
			return false;
		}
		catch (RuntimeException ex) {
			fail(projectId, AnalysisStatus.EMBEDDING, ex);
			return false;
		}
	}

	/**
	 * (EMBEDDING →) SUMMARIZING → READY. Starts from SUMMARIZING too, so a summary cut off by a crash, or retried
	 * because the model was unavailable, runs again when its trigger comes again. @return true when the project is
	 * READY
	 * @throws RetryableEventException only with {@link WhenModelUnavailable#RETRY}, when the model is unavailable; the
	 *         project stays SUMMARIZING
	 */
	boolean summarize(UUID projectId, Consumer<Project> inLastTransaction, WhenModelUnavailable whenUnavailable) {
		Project project = projects.findById(projectId).orElse(null);
		if (project == null || !BEFORE_SUMMARY.contains(project.getStatus())) {
			return skipped("summarize", project);
		}
		try {
			if (project.getStatus() == AnalysisStatus.EMBEDDING) {
				update(projectId, NOTHING, p -> p.transitionTo(AnalysisStatus.SUMMARIZING));
			}
			RepoSummary summary = summary(project);
			update(projectId, inLastTransaction, p -> p.complete(summary));
			log.info("Analysis READY in {} ms", Duration.between(project.getAnalysisStartedAt(), Instant.now()).toMillis());
			return true;
		}
		catch (EventAlreadyProcessed ex) {
			return duplicate(ex);
		}
		catch (ModelUnavailableException ex) {
			if (whenUnavailable == WhenModelUnavailable.RETRY) {
				log.warn("Summary not written: model unavailable; the error handler retries or dead-letters it");
				throw new RetryableEventException(ex);
			}
			fail(projectId, AnalysisStatus.SUMMARIZING, ex);
			return false;
		}
		catch (RuntimeException ex) {
			fail(projectId, AnalysisStatus.SUMMARIZING, ex);
			return false;
		}
	}

	/**
	 * Kafka (task 2.4): the error handler gave up on an event of this project and sent it to the dead-letter topic. A
	 * project still in a running stage is marked FAILED, so it does not wait for {@link StaleAnalysisRecovery}. The
	 * reason is the first {@link DomainException} in {@code ex}'s causes (for example the model is unavailable), else
	 * the general sentence for the stage.
	 */
	void failDeadLettered(UUID projectId, Exception ex) {
		Project project = projects.findById(projectId).orElse(null);
		if (project == null || !RUNNING.contains(project.getStatus())) {
			skipped("dead letter", project);
			return;
		}
		fail(projectId, project.getStatus(), userFacing(ex));
	}

	private ClonedRepo clone(Project project) {
		RemoteHead head = new RemoteHead(project.getCommitSha(), project.getDefaultBranch());
		ClonedRepo clone = repoFetcher.cloneShallow(GithubUrl.parse(project.getGithubUrl()), head, workDir);
		if (!clone.commitSha().equals(head.commitSha())) {
			// Recording the new commit could collide with another project at that commit (unique url + commit).
			clone.close();
			throw new AnalysisFailure(BRANCH_MOVED);
		}
		return clone;
	}

	/** The SUMMARY call. The build files and README were stored by ingest, so this works in another JVM too. */
	private RepoSummary summary(Project project) {
		Project parsed = projects.findById(project.getId()).orElseThrow();
		Map<String, Object> variables = new HashMap<>();
		variables.put("repoName", parsed.getOwner() + "/" + parsed.getRepoName());
		variables.put("goal", parsed.getGoal());
		variables.put("domain", parsed.getDomain());
		variables.put("repoMap", parsed.getRepoMap());
		variables.put("buildFiles", parsed.getBuildFiles());
		variables.put("readme", parsed.getReadme());
		SummaryOutput output = llm.call(
				new LlmRequest<>(LlmPurpose.SUMMARY, variables, SummaryOutput.class, parsed.getId(), null));
		return summaryValidator.validate(output, codebaseQueries.knownNames(parsed.getId()));
	}

	/** One short transaction: {@code first} (the caller's step), then {@code change}, then commit. */
	private void update(UUID projectId, Consumer<Project> first, Consumer<Project> change) {
		transaction.executeWithoutResult(status -> {
			Project project = projects.findById(projectId).orElseThrow();
			first.accept(project);
			change.accept(project);
		});
	}

	private static boolean skipped(String stage, Project project) {
		log.info("Stage {} skipped: project is {}", stage, project == null ? "missing" : project.getStatus());
		return false;
	}

	private static boolean duplicate(EventAlreadyProcessed ex) {
		log.info("Stage rolled back: {}", ex.getMessage());
		return false;
	}

	private void fail(UUID projectId, AnalysisStatus stage, RuntimeException ex) {
		String reason = reason(stage, ex);
		log.warn("Analysis FAILED during {}: {}", stage, reason, ex);
		try {
			update(projectId, NOTHING, p -> p.fail(reason));
		}
		catch (RuntimeException secondary) {
			// For example the database is down. StaleAnalysisRecovery marks it FAILED on the next start.
			log.error("Could not mark the project FAILED", secondary);
		}
	}

	private static RuntimeException userFacing(Exception ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof DomainException domain) {
				return domain;
			}
		}
		return ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
	}

	/** What the user sees. Messages we wrote for users pass through; anything else gets a general sentence. */
	static String reason(AnalysisStatus stage, RuntimeException ex) {
		if (ex instanceof DomainException || ex instanceof AnalysisFailure) {
			return ex.getMessage();
		}
		String step = switch (stage) {
			case CLONING -> "cloning the repository";
			case PARSING -> "reading the code";
			case EMBEDDING -> "indexing the code for search";
			case SUMMARIZING -> "summarizing the architecture";
			default -> "analyzing the repository";
		};
		return "Analysis failed while " + step + ". Use reanalyzeProject to retry.";
	}

}
