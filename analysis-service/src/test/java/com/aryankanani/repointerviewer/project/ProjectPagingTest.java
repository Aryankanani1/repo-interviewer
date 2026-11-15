package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectPage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keyset paging of {@link AnalysisApi#page} (task 1.11; moved from the app's GraphQL test in task 4.1, where the
 * cursor round trip is still tested).
 */
@IntegrationTest
class ProjectPagingTest {

	private final AnalysisApi analysis;

	private final JdbcTemplate jdbc;

	ProjectPagingTest(@Autowired AnalysisApi analysis, @Autowired JdbcTemplate jdbc) {
		this.analysis = analysis;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void deleteAllProjects() {
		jdbc.update("DELETE FROM project");
	}

	@Test
	void pagesAreNewestFirstAcrossThreePages() {
		insertProject(1, "repo-1", at(10));
		insertProject(2, "repo-2", at(11));
		// Same created_at: the id breaks the tie (DESC, so repo-4 comes first). They sit on both sides
		// of the page 1 / page 2 boundary, so a wrong keyset would skip or repeat one of them.
		insertProject(3, "repo-3", at(12));
		insertProject(4, "repo-4", at(12));
		insertProject(5, "repo-5", at(13));

		ProjectDto last = assertPage(null, List.of("repo-5", "repo-4"), true);
		last = assertPage(last, List.of("repo-3", "repo-2"), true);
		assertPage(last, List.of("repo-1"), false);
	}

	/** One page of 2 after {@code after}; returns its last project, where the next page starts. */
	private ProjectDto assertPage(ProjectDto after, List<String> expectedRepos, boolean expectedHasNext) {
		ProjectPage page = after == null ? analysis.page(2, null, null)
				: analysis.page(2, after.createdAt(), after.id());
		assertThat(page.items()).extracting(ProjectDto::repoName).containsExactlyElementsOf(expectedRepos);
		assertThat(page.hasNext()).isEqualTo(expectedHasNext);
		return page.items().getLast();
	}

	/** A READY project with a predictable id (…0001, …0002, ...) and the given created_at. */
	private void insertProject(int number, String repoName, OffsetDateTime createdAt) {
		UUID id = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(number));
		jdbc.update("""
				INSERT INTO project (id, github_url, owner, repo_name, commit_sha, status, created_at, updated_at)
				VALUES (?, ?, 'acme', ?, '0123456789abcdef0123456789abcdef01234567', 'READY', ?, ?)
				""", id, "https://github.com/acme/" + repoName, repoName, createdAt, createdAt);
	}

	private static OffsetDateTime at(int hour) {
		return OffsetDateTime.of(2026, 10, 20, hour, 0, 0, 0, ZoneOffset.UTC);
	}

}
