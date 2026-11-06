package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline;
import com.aryankanani.repointerviewer.RecordingAnalysisPipeline.Start;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.9: {@code Mutation.reanalyzeProject}, and {@code Project.summary} read back from JSONB. */
@IntegrationTest
class ReanalyzeProjectTest {

	private static final String REANALYZE = """
			mutation ($input: ReanalyzeProjectInput!) {
			  reanalyzeProject(input: $input) {
			    project { id status failureReason }
			    errors { code message field }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final JdbcTemplate jdbc;

	private final RecordingAnalysisPipeline pipeline;

	ReanalyzeProjectTest(@Autowired GraphQlTester graphQlTester, @Autowired JdbcTemplate jdbc,
			@Autowired RecordingAnalysisPipeline pipeline) {
		this.graphQlTester = graphQlTester;
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

		GraphQlTester.Response response = reanalyze(id);

		response.path("reanalyzeProject.errors").entityList(Object.class).hasSize(0);
		response.path("reanalyzeProject.project.status").entity(String.class).isEqualTo("SUBMITTED");
		response.path("reanalyzeProject.project.failureReason").valueIsNull();
		assertThat(pipeline.starts()).containsExactly(new Start(id, true));
	}

	@Test
	void aProjectThatDidNotFailIsReturnedUnchanged() {
		UUID id = project("READY", null);

		GraphQlTester.Response response = reanalyze(id);

		response.path("reanalyzeProject.project.status").entity(String.class).isEqualTo("READY");
		assertThat(pipeline.starts()).isEmpty();
	}

	@Test
	void anUnknownProjectIsNotFound() {
		GraphQlTester.Response response = reanalyze(UUID.randomUUID());

		response.path("reanalyzeProject.project").valueIsNull();
		response.path("reanalyzeProject.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND");
		response.path("reanalyzeProject.errors[0].field").entityList(String.class).containsExactly("input", "projectId");
	}

	@Test
	void theStoredSummaryIsReadable() {
		UUID id = project("READY", null);
		jdbc.update("UPDATE project SET summary = CAST(? AS jsonb) WHERE id = ?", """
				{"oneLiner": "A small order service.", "architectureStyle": "layered monolith",
				 "techStack": ["Spring Boot"], "notablePatterns": [], "riskAreas": ["Refund races in OrderService"],
				 "modules": [{"name": "order", "responsibility": "Orders", "keyClasses": ["OrderService"]}]}
				""", id);

		graphQlTester.document("""
				query ($id: ID!) { project(id: $id) { summary { oneLiner riskAreas modules { name keyClasses } } } }
				""").variable("id", id).execute()
				.path("project.summary.oneLiner").entity(String.class).isEqualTo("A small order service.")
				.path("project.summary.riskAreas[0]").entity(String.class).isEqualTo("Refund races in OrderService")
				.path("project.summary.modules[0].keyClasses[0]").entity(String.class).isEqualTo("OrderService");
	}

	private GraphQlTester.Response reanalyze(UUID id) {
		return graphQlTester.document(REANALYZE).variable("input", Map.of("projectId", id)).execute();
	}

	private UUID project(String status, String failureReason) {
		String repo = "shop-" + UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO project (github_url, owner, repo_name, commit_sha, default_branch, status, failure_reason)
				VALUES (?, 'acme', ?, '1111111111111111111111111111111111111111', 'main', ?, ?)
				RETURNING id
				""", UUID.class, "https://github.com/acme/" + repo, repo, status, failureReason);
	}

}
