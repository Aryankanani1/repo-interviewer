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
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Analyzes one project, start to finish (LLD §6.3):
 * SUBMITTED → CLONING → PARSING → EMBEDDING → SUMMARIZING → READY, or FAILED with a reason the user can read.
 *
 * <p>Each status change is its own short transaction. The slow work (clone, embeddings, the model call) runs
 * outside any transaction, so no database connection or row lock is held while waiting on the network.
 *
 * <p>The clone is deleted as soon as PARSING is done: everything later works from the database. The build files'
 * dependencies and the README for the summary are handed from PARSING to SUMMARIZING in memory, which works
 * because Phase 1 runs all stages in one job. Phase 2 splits the stages across Kafka consumers and will have to
 * store them.
 */
@Component
class AnalysisJob {

	static final String BRANCH_MOVED = "The repository changed since you submitted it. Submit it again to analyze "
			+ "the latest commit.";

	private static final Logger log = LoggerFactory.getLogger(AnalysisJob.class);

	private final ProjectRepository projects;

	private final TransactionTemplate transaction;

	private final RepoFetcher repoFetcher;

	private final CodebaseIndexer indexer;

	private final EmbeddingService embeddingService;

	private final CodebaseQueries codebaseQueries;

	private final LlmGateway llm;

	private final SummaryValidator summaryValidator;

	private final Path workDir;

	AnalysisJob(ProjectRepository projects, PlatformTransactionManager transactionManager, RepoFetcher repoFetcher,
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

	/**
	 * Runs the analysis if the project is still SUBMITTED; otherwise does nothing, so starting it twice is safe.
	 * Never throws: a failure is stored on the project.
	 */
	void run(UUID projectId) {
		MDC.put("projectId", projectId.toString());
		try {
			Project project = start(projectId);
			if (project != null) {
				analyze(project);
			}
		}
		finally {
			MDC.remove("projectId");
		}
	}

	/** SUBMITTED → CLONING in one transaction; null when this job should not run. */
	private Project start(UUID projectId) {
		try {
			return transaction.execute(status -> {
				Project project = projects.findById(projectId).orElse(null);
				if (project == null || project.getStatus() != AnalysisStatus.SUBMITTED) {
					log.info("Analysis not started: project is {}", project == null ? "missing" : project.getStatus());
					return null;
				}
				project.startAnalysis();
				return project;
			});
		}
		catch (OptimisticLockingFailureException ex) {
			// Another job changed the row between our read and our write, and owns the analysis now.
			log.info("Analysis not started: another job started it first");
			return null;
		}
	}

	private void analyze(Project project) {
		UUID projectId = project.getId();
		AnalysisStatus stage = AnalysisStatus.CLONING;
		long started = System.nanoTime();
		try {
			IndexResult index;
			try (ClonedRepo clone = clone(project)) {
				stage = AnalysisStatus.PARSING;
				update(projectId, p -> p.transitionTo(AnalysisStatus.PARSING));
				index = indexer.index(projectId, clone.directory());
				update(projectId, p -> p.recordIndex(index.fileCount(), index.javaFileCount(), index.totalLines(),
						index.chunkCount(), index.primaryLanguage(), index.repoMap()));
			}
			log.info("Parsed {}", index);

			stage = AnalysisStatus.EMBEDDING;
			int embedded = embeddingService.embedPending(projectId);
			log.info("Embedded {} chunks", embedded);

			stage = AnalysisStatus.SUMMARIZING;
			update(projectId, p -> p.transitionTo(AnalysisStatus.SUMMARIZING));
			RepoSummary summary = summarize(project, index);

			update(projectId, p -> p.complete(summary));
			log.info("Analysis READY in {} ms", (System.nanoTime() - started) / 1_000_000);
		}
		catch (RuntimeException ex) {
			fail(projectId, stage, ex);
		}
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

	private RepoSummary summarize(Project project, IndexResult index) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("repoName", project.getOwner() + "/" + project.getRepoName());
		variables.put("goal", project.getGoal());
		variables.put("domain", project.getDomain());
		variables.put("repoMap", index.repoMap());
		variables.put("buildFiles", index.buildFiles());
		variables.put("readme", index.readme());
		SummaryOutput output = llm.call(
				new LlmRequest<>(LlmPurpose.SUMMARY, variables, SummaryOutput.class, project.getId(), null));
		return summaryValidator.validate(output, codebaseQueries.knownNames(project.getId()));
	}

	/** Loads the project, changes it, and commits, all in one short transaction. */
	private void update(UUID projectId, Consumer<Project> change) {
		transaction.executeWithoutResult(status -> change.accept(projects.findById(projectId).orElseThrow()));
	}

	private void fail(UUID projectId, AnalysisStatus stage, RuntimeException ex) {
		String reason = reason(stage, ex);
		log.warn("Analysis FAILED during {}: {}", stage, reason, ex);
		try {
			update(projectId, p -> p.fail(reason));
		}
		catch (RuntimeException secondary) {
			// For example the database is down. StaleAnalysisRecovery marks it FAILED on the next start.
			log.error("Could not mark the project FAILED", secondary);
		}
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
