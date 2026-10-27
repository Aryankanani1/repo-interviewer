package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.9 acceptance tests: {@link AnalysisJob} end to end on the fixture repository, with real Postgres and
 * the fakes for GitHub ({@link LocalDirectoryRepoFetcher}), Ollama ({@link FakeEmbeddingModel}) and Claude
 * ({@link FakeLlmGateway}). The job is called directly, so each test waits for it to finish.
 */
@IntegrationTest
class AnalysisJobTest {

	private static final int FIXTURE_FILES = 11;

	private static final int FIXTURE_JAVA_FILES = 7;

	private static final int FIXTURE_CHUNKS = 29;

	private final AnalysisJob job;

	private final ProjectRepository projects;

	private final ProjectService projectService;

	private final JdbcTemplate jdbc;

	private final FakeEmbeddingModel embeddingModel;

	private final FakeLlmGateway llm;

	private final LocalDirectoryRepoFetcher repoFetcher;

	private final Path workDir;

	AnalysisJobTest(@Autowired AnalysisJob job, @Autowired ProjectRepository projects,
			@Autowired ProjectService projectService, @Autowired JdbcTemplate jdbc,
			@Autowired FakeEmbeddingModel embeddingModel, @Autowired FakeLlmGateway llm,
			@Autowired LocalDirectoryRepoFetcher repoFetcher, @Autowired AppProperties properties) {
		this.job = job;
		this.projects = projects;
		this.projectService = projectService;
		this.jdbc = jdbc;
		this.embeddingModel = embeddingModel;
		this.llm = llm;
		this.repoFetcher = repoFetcher;
		this.workDir = properties.repo().workDir();
	}

	@BeforeEach
	void resetState() {
		jdbc.update("DELETE FROM project");
		embeddingModel.reset();
		llm.reset();
		repoFetcher.reset();
	}

	/** Acceptance criterion: SUBMITTED to READY with stats, chunks, embeddings and summary saved. */
	@Test
	void theFixtureGoesFromSubmittedToReady() {
		UUID id = submitted("shop");

		job.run(id);

		Project project = projects.findById(id).orElseThrow();
		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.READY);
		assertThat(project.getFailureReason()).isNull();
		assertThat(project.getFileCount()).isEqualTo(FIXTURE_FILES);
		assertThat(project.getJavaFileCount()).isEqualTo(FIXTURE_JAVA_FILES);
		assertThat(project.getTotalLines()).isPositive().isEqualTo(
				jdbc.queryForObject("SELECT sum(line_count) FROM code_file WHERE project_id = ?", Integer.class, id));
		assertThat(project.getChunkCount()).isEqualTo(FIXTURE_CHUNKS).isEqualTo(count("code_chunk", id));
		assertThat(project.getPrimaryLanguage()).isEqualTo("java");
		assertThat(unembedded(id)).isZero();
		assertThat(project.getRepoMap()).startsWith("./\n  README.md [DOC]").contains("OrderService [SERVICE]");
		assertThat(project.getSummary().oneLiner()).isNotBlank();
		assertThat(project.getSummary().modules()).flatExtracting(RepoSummary.ModuleSummary::keyClasses)
				.contains("OrderService");
		assertThat(project.getAnalysisStartedAt()).isNotNull();
		assertThat(project.getAnalysisCompletedAt()).isAfterOrEqualTo(project.getAnalysisStartedAt());
	}

	@Test
	void theSummaryPromptGetsTheRepoMapTheDependenciesAndTheReadme() {
		UUID id = submitted("shop");

		job.run(id);

		assertThat(llm.requests()).singleElement().satisfies(request -> {
			assertThat(request.purpose()).isEqualTo(LlmPurpose.SUMMARY);
			assertThat(request.projectId()).isEqualTo(id);
			assertThat(request.sessionId()).isNull();
			assertThat(request.variables()).containsEntry("repoName", "acme/shop")
					.containsEntry("goal", "Learn Spring Boot")
					.containsEntry("repoMap", projects.findById(id).orElseThrow().getRepoMap());
			assertThat((String) request.variables().get("buildFiles")).startsWith("pom.xml\n")
					.contains("- parent org.springframework.boot:spring-boot-starter-parent")
					.contains("- org.springframework.boot:spring-boot-starter-webmvc");
			assertThat((String) request.variables().get("readme")).startsWith("# Shop\n");
		});
	}

	/** Acceptance criterion: a failure in EMBEDDING leaves FAILED with a readable reason. */
	@Test
	void anEmbeddingFailureLeavesFailedWithAReadableReason() {
		UUID id = submitted("shop");
		embeddingModel.failOnCall(1);

		job.run(id);

		Project project = projects.findById(id).orElseThrow();
		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.getFailureReason())
				.isEqualTo("Analysis failed while indexing the code for search. Use reanalyzeProject to retry.");
		assertThat(project.getFileCount()).isEqualTo(FIXTURE_FILES);   // PARSING had finished
		assertThat(unembedded(id)).isEqualTo(FIXTURE_CHUNKS);
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isZero();             // stopped before the paid step
	}

	@Test
	void aMissingRepositoryKeepsItsOwnMessage() {
		UUID id = submitted("gone");
		repoFetcher.markMissing("acme/gone");

		job.run(id);

		Project project = projects.findById(id).orElseThrow();
		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.getFailureReason()).isEqualTo("Repository not found. It must exist and be public.");
		assertThat(project.getFileCount()).isNull();
	}

	@Test
	void aBranchThatMovedSinceSubmitFailsInsteadOfMislabelingTheCommit() {
		UUID id = submitted("moving");
		repoFetcher.setClonedCommit("acme/moving", "2222222222222222222222222222222222222222");

		job.run(id);

		Project project = projects.findById(id).orElseThrow();
		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.getFailureReason()).isEqualTo(AnalysisJob.BRANCH_MOVED);
		assertThat(count("code_file", id)).isZero();
	}

	@Test
	void anUnavailableModelKeepsItsOwnMessage() {
		UUID id = submitted("shop");
		llm.enqueueFailure(LlmPurpose.SUMMARY, new LlmUnavailableException(new IllegalStateException("down")));

		job.run(id);

		Project project = projects.findById(id).orElseThrow();
		assertThat(project.getStatus()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.getFailureReason()).isEqualTo("The AI model is not available right now. Try again in a minute.");
		assertThat(unembedded(id)).isZero();   // EMBEDDING had finished
	}

	@Test
	void aProjectThatIsNotSubmittedIsLeftAlone() {
		UUID id = submitted("shop");
		job.run(id);
		long version = jdbc.queryForObject("SELECT version FROM project WHERE id = ?", Long.class, id);

		job.run(id);   // READY now: a second start must do nothing

		assertThat(jdbc.queryForObject("SELECT version FROM project WHERE id = ?", Long.class, id)).isEqualTo(version);
		assertThat(llm.calls(LlmPurpose.SUMMARY)).isEqualTo(1);
	}

	@Test
	void aRetryAfterReanalyzeReplacesTheEarlierIndex() {
		UUID id = submitted("shop");
		embeddingModel.failOnCall(1);
		job.run(id);
		embeddingModel.reset();

		projectService.reanalyze(id);
		job.run(id);

		assertThat(projects.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.READY);
		assertThat(count("code_file", id)).isEqualTo(FIXTURE_FILES);
		assertThat(count("code_chunk", id)).isEqualTo(FIXTURE_CHUNKS);
	}

	@Test
	void theCloneIsDeletedWhetherTheAnalysisWorkedOrNot() throws IOException {
		long before = clones();
		UUID worked = submitted("shop");
		UUID failed = submitted("moving");
		repoFetcher.setClonedCommit("acme/moving", "2222222222222222222222222222222222222222");

		job.run(worked);
		job.run(failed);

		assertThat(clones()).isEqualTo(before);
	}

	private UUID submitted(String repo) {
		GithubUrl url = GithubUrl.parse("https://github.com/acme/" + repo);
		Project project = new Project(url, new RemoteHead(LocalDirectoryRepoFetcher.DEFAULT_SHA, "main"),
				new SubmitProjectCommand(url.normalized(), "Learn Spring Boot", null, null));
		return projects.save(project).getId();
	}

	private int count(String table, UUID projectId) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?", Integer.class, projectId);
	}

	private int unembedded(UUID projectId) {
		return jdbc.queryForObject("SELECT count(*) FROM code_chunk WHERE project_id = ? AND embedding IS NULL",
				Integer.class, projectId);
	}

	private long clones() throws IOException {
		if (!Files.isDirectory(workDir)) {
			return 0;
		}
		try (Stream<Path> entries = Files.list(workDir)) {
			return entries.filter(path -> path.getFileName().toString().startsWith("clone-")).count();
		}
	}

}
