package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 1.11 acceptance tests for {@code Query.project} and {@code Query.projects}, over analysis-service's answers
 * (task 4.1). The keyset itself, ties included, is tested in analysis-service's {@code ProjectPagingTest}; here, that
 * a GraphQL cursor leads to the right next page ({@code ProjectController} writes it and reads it back).
 */
@IntegrationTest
class ProjectQueriesTest {

	private static final String PROJECT_BY_ID = """
			query ($id: ID!) {
			  project(id: $id) { id githubUrl name commitSha status createdAt }
			}
			""";

	private static final String PROJECTS_PAGE = """
			query ($first: Int, $after: String) {
			  projects(first: $first, after: $after) {
			    edges { cursor node { name } }
			    pageInfo { hasNextPage endCursor }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi analysis;

	ProjectQueriesTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
	}

	@BeforeEach
	void deleteAllProjects() {
		analysis.reset();
	}

	@Test
	void projectReturnsTheProjectWhenFound() {
		ProjectDto project = analysis.add(AnalysisStatus.READY);

		graphQlTester.document(PROJECT_BY_ID).variable("id", project.id()).execute()
				.path("project.id").entity(String.class).isEqualTo(project.id().toString())
				.path("project.githubUrl").entity(String.class).isEqualTo(project.githubUrl())
				.path("project.name").entity(String.class).isEqualTo("acme/" + project.repoName())
				.path("project.commitSha").entity(String.class).isEqualTo(project.commitSha())
				.path("project.status").entity(String.class).isEqualTo("READY");
	}

	@Test
	void projectReturnsNullWhenNotFound() {
		graphQlTester.document(PROJECT_BY_ID).variable("id", UUID.randomUUID()).execute()
				.path("project").valueIsNull();
	}

	@Test
	void createdAtIsAnIso8601String() {
		ProjectDto project = analysis.add(AnalysisStatus.READY);

		String value = graphQlTester.document(PROJECT_BY_ID).variable("id", project.id()).execute()
				.path("project.createdAt").entity(String.class).get();

		// Any offset is fine ("Z", "+00:00", "-04:00") as long as it is ISO-8601 and the same instant.
		assertThat(OffsetDateTime.parse(value).toInstant()).isEqualTo(project.createdAt());
	}

	@Test
	void projectsPagesNewestFirstAcrossThreePages() {
		List<String> newestFirst = IntStream.range(0, 5).mapToObj(i -> analysis.add(AnalysisStatus.READY))
				.map(project -> "acme/" + project.repoName()).toList().reversed();

		String afterPage1 = assertPage(null, newestFirst.subList(0, 2), true);
		String afterPage2 = assertPage(afterPage1, newestFirst.subList(2, 4), true);
		assertPage(afterPage2, newestFirst.subList(4, 5), false);
	}

	/** Fetches one page of 2 and checks names in order; returns the cursor for the next page. */
	private String assertPage(String after, List<String> expectedNames, boolean expectedHasNextPage) {
		GraphQlTester.Request<?> request = graphQlTester.document(PROJECTS_PAGE).variable("first", 2);
		if (after != null) {
			request = request.variable("after", after);
		}
		GraphQlTester.Response response = request.execute();

		response.path("projects.edges[*].node.name").entityList(String.class)
				.containsExactly(expectedNames.toArray(String[]::new));
		response.path("projects.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(expectedHasNextPage);
		return response.path("projects.pageInfo.endCursor").entity(String.class).get();
	}

}
