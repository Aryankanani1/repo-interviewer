package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 1.1 acceptance tests for {@code Query.project} and {@code Query.projects}.
 *
 * <p>Written before the implementation. They use only the GraphQL schema and plain SQL inserts,
 * so they do not depend on how the entity, repository, service or controller are designed.
 */
@IntegrationTest
class ProjectQueriesTest {

	private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

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
	private final JdbcTemplate jdbc;

	ProjectQueriesTest(@Autowired GraphQlTester graphQlTester, @Autowired JdbcTemplate jdbc) {
		this.graphQlTester = graphQlTester;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void deleteAllProjects() {
		jdbc.update("DELETE FROM project");
	}

	@Test
	void projectReturnsTheProjectWhenFound() {
		UUID id = insertProject(1, "repo-1", at(10));

		graphQlTester.document(PROJECT_BY_ID).variable("id", id).execute()
				.path("project.id").entity(String.class).isEqualTo(id.toString())
				.path("project.githubUrl").entity(String.class).isEqualTo("https://github.com/acme/repo-1")
				.path("project.name").entity(String.class).isEqualTo("acme/repo-1")
				.path("project.commitSha").entity(String.class).isEqualTo(SHA)
				.path("project.status").entity(String.class).isEqualTo("READY");
	}

	@Test
	void projectReturnsNullWhenNotFound() {
		graphQlTester.document(PROJECT_BY_ID).variable("id", UUID.randomUUID()).execute()
				.path("project").valueIsNull();
	}

	@Test
	void createdAtIsAnIso8601String() {
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 10, 20, 10, 30, 15, 0, ZoneOffset.UTC);
		UUID id = insertProject(1, "repo-1", createdAt);

		String value = graphQlTester.document(PROJECT_BY_ID).variable("id", id).execute()
				.path("project.createdAt").entity(String.class).get();

		// Any offset is fine ("Z", "+00:00", "-04:00") as long as it is ISO-8601 and the same instant.
		assertThat(OffsetDateTime.parse(value).toInstant()).isEqualTo(createdAt.toInstant());
	}

	@Test
	void projectsPagesNewestFirstAcrossThreePages() {
		insertProject(1, "repo-1", at(10));
		insertProject(2, "repo-2", at(11));
		// Same created_at: the id breaks the tie (DESC, so repo-4 comes first). They sit on both sides
		// of the page 1 / page 2 boundary, so a wrong keyset would skip or repeat one of them.
		insertProject(3, "repo-3", at(12));
		insertProject(4, "repo-4", at(12));
		insertProject(5, "repo-5", at(13));

		String afterPage1 = assertPage(null, List.of("acme/repo-5", "acme/repo-4"), true);
		String afterPage2 = assertPage(afterPage1, List.of("acme/repo-3", "acme/repo-2"), true);
		assertPage(afterPage2, List.of("acme/repo-1"), false);
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

	/** Inserts a READY project with a predictable id (…0001, …0002, ...) and the given created_at. */
	private UUID insertProject(int number, String repoName, OffsetDateTime createdAt) {
		UUID id = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(number));
		jdbc.update("""
				INSERT INTO project (id, github_url, owner, repo_name, commit_sha, status, created_at, updated_at)
				VALUES (?, ?, 'acme', ?, ?, 'READY', ?, ?)
				""", id, "https://github.com/acme/" + repoName, repoName, SHA, createdAt, createdAt);
		return id;
	}

	private static OffsetDateTime at(int hour) {
		return OffsetDateTime.of(2026, 10, 20, hour, 0, 0, 0, ZoneOffset.UTC);
	}

}
