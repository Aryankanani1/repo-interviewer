package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline.Start;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.SubmitProjectRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.2 acceptance tests for submitting a project, on this service's {@link AnalysisApi} (task 4.1: they were
 * GraphQL tests in the app; the app's {@code SubmitProjectTest} now checks only how GraphQL shows the answer).
 */
@IntegrationTest
class SubmitProjectTest {

	private final AnalysisApi analysis;

	private final JdbcTemplate jdbc;

	private final LocalDirectoryRepoFetcher repoFetcher;

	private final RecordingAnalysisPipeline pipeline;

	private final DataSource dataSource;

	SubmitProjectTest(@Autowired AnalysisApi analysis, @Autowired JdbcTemplate jdbc,
			@Autowired LocalDirectoryRepoFetcher repoFetcher, @Autowired RecordingAnalysisPipeline pipeline,
			@Autowired DataSource dataSource) {
		this.analysis = analysis;
		this.jdbc = jdbc;
		this.repoFetcher = repoFetcher;
		this.pipeline = pipeline;
		this.dataSource = dataSource;
	}

	@BeforeEach
	void resetState() {
		jdbc.update("DELETE FROM project");
		repoFetcher.reset();
		pipeline.reset();
	}

	@Test
	void newRepositoryIsSavedAsSubmittedAndAnalysisStartsAfterCommit() {
		ProjectDto project = submit("HTTPS://GitHub.com/Acme/Shop.git/");

		assertThat(project.owner() + "/" + project.repoName()).isEqualTo("acme/shop");
		assertThat(project.githubUrl()).isEqualTo("https://github.com/acme/shop");
		assertThat(project.commitSha()).isEqualTo(LocalDirectoryRepoFetcher.DEFAULT_SHA);
		assertThat(project.status()).isEqualTo(AnalysisStatus.SUBMITTED);
		// rowCommitted = true: another connection could already see the row when start() ran.
		assertThat(pipeline.starts()).containsExactly(new Start(project.id(), true));
	}

	@Test
	void invalidUrlIsInvalidUrlOnTheGithubUrlField() {
		assertThatThrownBy(() -> submit("https://gitlab.com/acme/shop")).isInstanceOfSatisfying(DomainException.class,
				ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_URL);
					assertThat(ex.field()).contains("githubUrl");
				});
		assertThat(projectCount()).isZero();
		assertThat(pipeline.starts()).isEmpty();
	}

	@Test
	void duplicateSubmitReturnsTheSameProject() {
		UUID first = submit("https://github.com/acme/shop").id();
		UUID second = submit("https://github.com/Acme/Shop/").id();

		assertThat(second).isEqualTo(first);
		assertThat(projectCount()).isEqualTo(1);
		assertThat(pipeline.starts()).hasSize(1);   // analysis runs once per (url, commit)
	}

	/**
	 * The insert race, made deterministic. The "winner" inserts the same (url, commit) in a transaction it
	 * keeps open. submit() cannot see that uncommitted row, so it inserts too, and Postgres makes its INSERT
	 * wait on the unique index. When the winner commits, the waiting INSERT fails with a unique violation,
	 * and submit() must re-read and return the winner's row.
	 */
	@Test
	@Timeout(30)
	void insertRaceLoserReturnsTheWinnersProjectAndStartsNoAnalysis() throws Exception {
		UUID winnerId = UUID.randomUUID();
		// Executor first: try-with-resources closes in reverse, so the connection closes (and rolls back,
		// releasing the lock) before executor.close() waits for the submit thread. No hang if an assert fails.
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
				Connection winner = dataSource.getConnection()) {
			winner.setAutoCommit(false);
			try (PreparedStatement insert = winner.prepareStatement("""
					INSERT INTO project (id, github_url, owner, repo_name, commit_sha, status)
					VALUES (?, 'https://github.com/acme/shop', 'acme', 'shop', ?, 'SUBMITTED')
					""")) {
				insert.setObject(1, winnerId);
				insert.setString(2, LocalDirectoryRepoFetcher.DEFAULT_SHA);
				insert.executeUpdate();
			}

			Future<ProjectDto> loser = executor.submit(() -> submit("https://github.com/acme/shop"));
			awaitInsertBlockedOnLock(loser);
			winner.commit();

			assertThat(loser.get(10, TimeUnit.SECONDS).id()).isEqualTo(winnerId);
		}
		assertThat(projectCount()).isEqualTo(1);
		assertThat(pipeline.starts()).isEmpty();   // the loser's transaction rolled back, so its event was dropped
	}

	@Test
	void newCommitOfTheSameRepositoryIsANewProject() {
		UUID first = submit("https://github.com/acme/shop").id();
		repoFetcher.setHead("acme/shop", "2222222222222222222222222222222222222222");
		UUID second = submit("https://github.com/acme/shop").id();

		assertThat(second).isNotEqualTo(first);
		assertThat(projectCount()).isEqualTo(2);
	}

	@Test
	void missingRepositoryIsRepoNotFoundOnTheGithubUrlField() {
		repoFetcher.markMissing("acme/private-repo");

		assertThatThrownBy(() -> submit("https://github.com/acme/private-repo"))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND);
					assertThat(ex.field()).contains("githubUrl");
				});
		assertThat(projectCount()).isZero();
		assertThat(pipeline.starts()).isEmpty();
	}

	@Test
	void failedProjectForTheSameCommitIsReturnedWithItsReason() {
		UUID failedId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO project (id, github_url, owner, repo_name, commit_sha, status, failure_reason)
				VALUES (?, 'https://github.com/acme/shop', 'acme', 'shop', ?, 'FAILED', 'Clone timed out')
				""", failedId, LocalDirectoryRepoFetcher.DEFAULT_SHA);

		ProjectDto project = submit("https://github.com/acme/shop");

		assertThat(project.id()).isEqualTo(failedId);
		assertThat(project.status()).isEqualTo(AnalysisStatus.FAILED);
		assertThat(project.failureReason()).isEqualTo("Clone timed out");
		assertThat(pipeline.starts()).isEmpty();   // retrying is reanalyze's job
	}

	@Test
	void contextTextIsTrimmedCappedAndBlankBecomesNull() {
		ProjectDto project = analysis.submit(new SubmitProjectRequest("https://github.com/acme/shop",
				"  " + "x".repeat(600) + "  ", "   ", null));

		assertThat(project.goal()).isEqualTo("x".repeat(500));
		assertThat(project.domain()).isNull();
	}

	private ProjectDto submit(String githubUrl) {
		return analysis.submit(new SubmitProjectRequest(githubUrl, null, null, null));
	}

	private int projectCount() {
		return jdbc.queryForObject("SELECT count(*) FROM project", Integer.class);
	}

	/** Waits until a session is blocked on a lock. In this test only submit()'s INSERT can be. */
	private void awaitInsertBlockedOnLock(Future<?> submit) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			int waiting = jdbc.queryForObject("""
					SELECT count(*) FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND datname = current_database()
					""", Integer.class);
			if (waiting > 0) {
				return;
			}
			assertThat(submit).as("submit() finished before reaching its INSERT").isNotDone();
			Thread.sleep(20);
		}
		throw new AssertionError("submit() never blocked on the unique index");
	}

}
