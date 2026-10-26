package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline.Start;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.2 acceptance tests for {@code Mutation.submitProject}. */
@IntegrationTest
class SubmitProjectTest {

	private static final String SUBMIT = """
			mutation ($input: SubmitProjectInput!) {
			  submitProject(input: $input) {
			    project { id name githubUrl commitSha status failureReason context { goal domain } }
			    errors { code message field }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final JdbcTemplate jdbc;

	private final LocalDirectoryRepoFetcher repoFetcher;

	private final RecordingAnalysisPipeline pipeline;

	private final DataSource dataSource;

	SubmitProjectTest(@Autowired GraphQlTester graphQlTester, @Autowired JdbcTemplate jdbc,
			@Autowired LocalDirectoryRepoFetcher repoFetcher, @Autowired RecordingAnalysisPipeline pipeline,
			@Autowired DataSource dataSource) {
		this.graphQlTester = graphQlTester;
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
		GraphQlTester.Response response = submit(Map.of("githubUrl", "HTTPS://GitHub.com/Acme/Shop.git/"));

		response.path("submitProject.errors").entityList(Object.class).hasSize(0);
		response.path("submitProject.project.name").entity(String.class).isEqualTo("acme/shop");
		response.path("submitProject.project.githubUrl").entity(String.class).isEqualTo("https://github.com/acme/shop");
		response.path("submitProject.project.commitSha").entity(String.class).isEqualTo(LocalDirectoryRepoFetcher.DEFAULT_SHA);
		response.path("submitProject.project.status").entity(String.class).isEqualTo("SUBMITTED");
		UUID id = response.path("submitProject.project.id").entity(UUID.class).get();

		// rowCommitted = true: another connection could already see the row when start() ran.
		assertThat(pipeline.starts()).containsExactly(new Start(id, true));
	}

	@Test
	void invalidUrlReturnsInvalidUrlOnTheGithubUrlField() {
		GraphQlTester.Response response = submit(Map.of("githubUrl", "https://gitlab.com/acme/shop"));

		response.path("submitProject.project").valueIsNull();
		response.path("submitProject.errors[0].code").entity(String.class).isEqualTo("INVALID_URL");
		response.path("submitProject.errors[0].field").entityList(String.class).containsExactly("input", "githubUrl");
		assertThat(projectCount()).isZero();
		assertThat(pipeline.starts()).isEmpty();
	}

	@Test
	void duplicateSubmitReturnsTheSameProject() {
		UUID first = submittedId(submit(Map.of("githubUrl", "https://github.com/acme/shop")));
		UUID second = submittedId(submit(Map.of("githubUrl", "https://github.com/Acme/Shop/")));

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

			Future<GraphQlTester.Response> loser = executor
					.submit(() -> submit(Map.of("githubUrl", "https://github.com/acme/shop")));
			awaitInsertBlockedOnLock(loser);
			winner.commit();

			GraphQlTester.Response response = loser.get(10, TimeUnit.SECONDS);
			response.path("submitProject.errors").entityList(Object.class).hasSize(0);
			response.path("submitProject.project.id").entity(UUID.class).isEqualTo(winnerId);
		}
		assertThat(projectCount()).isEqualTo(1);
		assertThat(pipeline.starts()).isEmpty();   // the loser's transaction rolled back, so its event was dropped
	}

	@Test
	void newCommitOfTheSameRepositoryIsANewProject() {
		UUID first = submittedId(submit(Map.of("githubUrl", "https://github.com/acme/shop")));
		repoFetcher.setHead("acme/shop", "2222222222222222222222222222222222222222");
		UUID second = submittedId(submit(Map.of("githubUrl", "https://github.com/acme/shop")));

		assertThat(second).isNotEqualTo(first);
		assertThat(projectCount()).isEqualTo(2);
	}

	@Test
	void missingRepositoryReturnsRepoNotFound() {
		repoFetcher.markMissing("acme/private-repo");

		GraphQlTester.Response response = submit(Map.of("githubUrl", "https://github.com/acme/private-repo"));

		response.path("submitProject.project").valueIsNull();
		response.path("submitProject.errors[0].code").entity(String.class).isEqualTo("REPO_NOT_FOUND");
		response.path("submitProject.errors[0].field").entityList(String.class).containsExactly("input", "githubUrl");
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

		GraphQlTester.Response response = submit(Map.of("githubUrl", "https://github.com/acme/shop"));

		response.path("submitProject.project.id").entity(UUID.class).isEqualTo(failedId);
		response.path("submitProject.project.status").entity(String.class).isEqualTo("FAILED");
		response.path("submitProject.project.failureReason").entity(String.class).isEqualTo("Clone timed out");
		assertThat(pipeline.starts()).isEmpty();   // retrying is reanalyzeProject's job
	}

	@Test
	void contextTextIsTrimmedCappedAndBlankBecomesNull() {
		Map<String, Object> input = new HashMap<>();
		input.put("githubUrl", "https://github.com/acme/shop");
		input.put("goal", "  " + "x".repeat(600) + "  ");
		input.put("domain", "   ");

		GraphQlTester.Response response = submit(input);

		response.path("submitProject.project.context.goal").entity(String.class).isEqualTo("x".repeat(500));
		response.path("submitProject.project.context.domain").valueIsNull();
	}

	private GraphQlTester.Response submit(Map<String, Object> input) {
		return graphQlTester.document(SUBMIT).variable("input", input).execute();
	}

	private static UUID submittedId(GraphQlTester.Response response) {
		return response.path("submitProject.project.id").entity(UUID.class).get();
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
