package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * {@code Mutation.reanalyzeProject} and the {@code Project.summary} it shows (task 4.1: the rules are tested in
 * analysis-service's {@code ReanalyzeProjectTest}).
 */
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

	private final FakeAnalysisApi analysis;

	ReanalyzeProjectTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
	}

	@BeforeEach
	void resetState() {
		analysis.reset();
	}

	@Test
	void aFailedProjectIsShownSubmittedAgain() {
		UUID id = analysis.add(AnalysisStatus.FAILED).id();

		GraphQlTester.Response response = reanalyze(id);

		response.path("reanalyzeProject.errors").entityList(Object.class).hasSize(0);
		response.path("reanalyzeProject.project.status").entity(String.class).isEqualTo("SUBMITTED");
		response.path("reanalyzeProject.project.failureReason").valueIsNull();
	}

	@Test
	void anUnknownProjectIsNotFoundOnTheProjectIdField() {
		GraphQlTester.Response response = reanalyze(UUID.randomUUID());

		response.path("reanalyzeProject.project").valueIsNull();
		response.path("reanalyzeProject.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND");
		response.path("reanalyzeProject.errors[0].field").entityList(String.class).containsExactly("input", "projectId");
	}

	@Test
	void theSummaryIsReadable() {
		UUID id = analysis.readyProject();

		graphQlTester.document("""
				query ($id: ID!) { project(id: $id) { summary { oneLiner riskAreas modules { name keyClasses } } } }
				""").variable("id", id).execute()
				.path("project.summary.oneLiner").entity(String.class)
				.isEqualTo("A small order service that places and refunds orders.")
				.path("project.summary.riskAreas[0]").entity(String.class)
				.isEqualTo("Concurrent refunds on the same order in OrderService#refund")
				.path("project.summary.modules[0].keyClasses[0]").entity(String.class).isEqualTo("OrderService");
	}

	private GraphQlTester.Response reanalyze(UUID id) {
		return graphQlTester.document(REANALYZE).variable("input", Map.of("projectId", id)).execute();
	}

}
