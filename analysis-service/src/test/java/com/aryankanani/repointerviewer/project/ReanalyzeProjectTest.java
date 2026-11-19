package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline.Start;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.RepoSummary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reanalyzing a project (task 1.12) on this service's {@link AnalysisApi} (task 4.1: they were GraphQL tests in the
 * app; the app's {@code ReanalyzeProjectTest} now checks only how GraphQL shows the answer).
 */
@IntegrationTest
class ReanalyzeProjectTest {

	private final AnalysisApi analysis;

	private final JdbcTemplate jdbc;

	private final RecordingAnalysisPipeline pipeline;

	ReanalyzeProjectTest(@Autowired AnalysisApi analysis, @Autowired JdbcTemplate jdbc,
			@Autowired RecordingAnalysisPipeline pipeline) {
		this.analysis = analysis;
		this.jdbc = jdbc;
		this.pipeline = pipeline;
	}

	@BeforeEach
	void resetState() {
		jdbc.update("DELETE FROM project");
		pipeline.reset();
	}

	@Test
	void aFailedProjectIsSubmittedAgainAndStartsAfterCommit() {
		UUID id = project("FAILED", "Analysis was interrupted. Use reanalyzeProject to retry.");

		ProjectDto project = analysis.reanalyze(id);

		assertThat(project.status()).isEqualTo(AnalysisStatus.SUBMITTED);
		assertThat(project.failureReason()).isNull();
		assertThat(pipeline.starts()).containsExactly(new Start(id, true));
	}

	@Test
	void aProjectThatDidNotFailIsReturnedUnchanged() {
		UUID id = project("READY", null);

		assertThat(analysis.reanalyze(id).status()).isEqualTo(AnalysisStatus.READY);
		assertThat(pipeline.starts()).isEmpty();
	}

	@Test
	void anUnknownProjectIsNotFoundOnTheProjectIdField() {
		assertThatThrownBy(() -> analysis.reanalyze(UUID.randomUUID()))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
					assertThat(ex.field()).contains("projectId");
				});
	}

	@Test
	void theStoredSummaryIsReadable() {
		UUID id = project("READY", null);
		jdbc.update("UPDATE project SET summary = CAST(? AS jsonb) WHERE id = ?", """
				{"oneLiner": "A small order service.", "architectureStyle": "layered monolith",
				 "techStack": ["Spring Boot"], "notablePatterns": [], "riskAreas": ["Refund races in OrderService"],
				 "modules": [{"name": "order", "responsibility": "Orders", "keyClasses": ["OrderService"]}]}
				""", id);

		RepoSummary summary = analysis.findByIds(List.of(id)).getFirst().summary();

		assertThat(summary.oneLiner()).isEqualTo("A small order service.");
		assertThat(summary.riskAreas()).containsExactly("Refund races in OrderService");
		assertThat(summary.modules().getFirst().keyClasses()).containsExactly("OrderService");
	}

	private UUID project(String status, String failureReason) {
		String repo = "shop-" + UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO project (user_id, github_url, owner, repo_name, commit_sha, default_branch, status,
				                     failure_reason)
				VALUES ('user-a', ?, 'acme', ?, '1111111111111111111111111111111111111111', 'main', ?, ?)
				RETURNING id
				""", UUID.class, "https://github.com/acme/" + repo, repo, status, failureReason);
	}

}
