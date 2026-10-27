package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.9 acceptance test: an analysis cut off by a restart becomes FAILED on the next start. */
@IntegrationTest
class StaleAnalysisRecoveryTest {

	private final StaleAnalysisRecovery recovery;

	private final JdbcTemplate jdbc;

	StaleAnalysisRecoveryTest(@Autowired StaleAnalysisRecovery recovery, @Autowired JdbcTemplate jdbc) {
		this.recovery = recovery;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void deleteProjects() {
		jdbc.update("DELETE FROM project");
	}

	/** Acceptance criterion. */
	@Test
	void aStaleParsingProjectBecomesFailed() {
		UUID stale = project("PARSING", "20 minutes");

		assertThat(recovery.recover()).isEqualTo(1);

		assertThat(status(stale)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT failure_reason FROM project WHERE id = ?", String.class, stale))
				.isEqualTo("Analysis was interrupted. Use reanalyzeProject to retry.");
		assertThat(jdbc.queryForObject("SELECT version FROM project WHERE id = ?", Long.class, stale)).isEqualTo(1);
	}

	@Test
	void everyRunningStageIsCoveredButOtherStatusesAreNot() {
		UUID cloning = project("CLONING", "1 hour");
		UUID embedding = project("EMBEDDING", "1 hour");
		UUID summarizing = project("SUMMARIZING", "1 hour");
		UUID submitted = project("SUBMITTED", "1 hour");
		UUID ready = project("READY", "1 hour");

		recovery.recover();

		assertThat(status(cloning)).isEqualTo("FAILED");
		assertThat(status(embedding)).isEqualTo("FAILED");
		assertThat(status(summarizing)).isEqualTo("FAILED");
		assertThat(status(submitted)).isEqualTo("SUBMITTED");
		assertThat(status(ready)).isEqualTo("READY");
	}

	/** It may still be running, for example on another app instance. */
	@Test
	void aRecentlyUpdatedProjectIsLeftAlone() {
		UUID recent = project("PARSING", "1 minute");

		assertThat(recovery.recover()).isZero();

		assertThat(status(recent)).isEqualTo("PARSING");
	}

	private UUID project(String status, String age) {
		String repo = "shop-" + UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO project (github_url, owner, repo_name, commit_sha, status, updated_at)
				VALUES (?, 'acme', ?, '1111111111111111111111111111111111111111', ?, now() - CAST(? AS interval))
				RETURNING id
				""", UUID.class, "https://github.com/acme/" + repo, repo, status, age);
	}

	private String status(UUID id) {
		return jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, id);
	}

}
