package com.aryankanani.repointerviewer.project;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Task 4.1 step 2a: {@code AnalysisApi} over HTTP, as a caller in another service will see it from step 2b. The
 * request bodies are written out as JSON on purpose: they are the wire format of the contract.
 */
@IntegrationTest
class AnalysisApiControllerTest {

	private static final String SERVICE = "src/main/java/com/example/shop/order/OrderService.java";

	private final MockMvc mvc;

	private final FixtureIndexer fixtures;

	private final LocalDirectoryRepoFetcher repoFetcher;

	private final JdbcTemplate jdbc;

	AnalysisApiControllerTest(@Autowired WebApplicationContext context, @Autowired FixtureIndexer fixtures,
			@Autowired LocalDirectoryRepoFetcher repoFetcher, @Autowired JdbcTemplate jdbc) {
		this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
		this.fixtures = fixtures;
		this.repoFetcher = repoFetcher;
		this.jdbc = jdbc;
	}

	@AfterEach
	void resetFetcher() {
		repoFetcher.reset();
	}

	@Test
	void submitReturnsTheNewProjectWithTheContextCleanedOnThisSide() throws Exception {
		String repo = "api-" + UUID.randomUUID();
		postJson("/internal/projects", """
				{"githubUrl": "https://github.com/acme/%s", "goal": "  Learn Kafka  ", "domain": " "}
				""".formatted(repo))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.owner").value("acme"))
				.andExpect(jsonPath("$.repoName").value(repo))
				.andExpect(jsonPath("$.commitSha").value(LocalDirectoryRepoFetcher.DEFAULT_SHA))
				.andExpect(jsonPath("$.status").value("SUBMITTED"))
				.andExpect(jsonPath("$.goal").value("Learn Kafka"))
				.andExpect(jsonPath("$.domain").value(nullValue()))
				.andExpect(jsonPath("$.stats").value(nullValue()))
				.andExpect(jsonPath("$.createdAt").isString());
	}

	@Test
	void anExpectedFailureIsAProblemDetailWithItsCodeAndField() throws Exception {
		postJson("/internal/projects", """
				{"githubUrl": "https://gitlab.com/acme/shop"}
				""")
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("INVALID_URL"))
				.andExpect(jsonPath("$.field").value("githubUrl"))
				.andExpect(jsonPath("$.detail").isString());

		repoFetcher.markMissing("acme/ghost");
		postJson("/internal/projects", """
				{"githubUrl": "https://github.com/acme/ghost"}
				""")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("REPO_NOT_FOUND"));
	}

	@Test
	void anUnknownProjectIsA404WithTheSameErrorAsBefore() throws Exception {
		mvc.perform(get("/internal/projects/{id}/context", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.field").value("projectId"))
				.andExpect(jsonPath("$.detail").value("Project not found."));
	}

	@Test
	void aReadyProjectsContextCodeNamesChunksAndSnippets() throws Exception {
		UUID project = fixtures.readyProject();

		mvc.perform(get("/internal/projects/{id}/context", project))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.summary.oneLiner").value("A small order service that places and refunds orders."))
				.andExpect(jsonPath("$.repoMap").value(containsString("OrderService")));

		mvc.perform(get("/internal/projects/{id}/code-names", project))
				.andExpect(jsonPath("$.paths").value(hasItem(SERVICE)))
				.andExpect(jsonPath("$.symbols").value(hasItem(startsWith("OrderService#refund"))));

		postJson("/internal/projects/" + project + "/chunks/search", """
				{"query": {"text": "How are refunds limited?", "targetPaths": [], "targetSymbols": ["OrderService#refund"],
				           "includeTests": false},
				 "maxTokens": 4000}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chunks[0].symbol").value(startsWith("OrderService#refund")))
				.andExpect(jsonPath("$.totalTokens").isNumber());

		postJson("/internal/projects/" + project + "/chunks/ranges", """
				{"ranges": [{"path": "%s", "startLine": 27, "endLine": 42}], "maxTokens": 4000}
				""".formatted(SERVICE))
				.andExpect(jsonPath("$.chunks").isNotEmpty())
				.andExpect(jsonPath("$.chunks[*].path").value(everyItem(startsWith(SERVICE))));

		postJson("/internal/snippets", """
				[{"projectId": "%s", "path": "%s", "startLine": 27, "endLine": 28},
				 {"projectId": "%s", "path": "src/Ghost.java", "startLine": 1, "endLine": 2}]
				""".formatted(project, SERVICE, project))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0]").value(fixtureLines(SERVICE, 27, 28)))
				.andExpect(jsonPath("$[1]").value(nullValue()));
	}

	@Test
	void findByIdsLeavesOutUnknownIds() throws Exception {
		UUID project = fixtures.newProject();
		mvc.perform(get("/internal/projects").param("ids", project + "," + UUID.randomUUID()))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(project.toString()));
	}

	/**
	 * Three projects dated 2000, so they are the oldest of the shared test database and no other test's projects get
	 * in between. A page after a made-up position works too: the keyset is values, not a row.
	 */
	@Test
	void pagesAreNewestFirstAndTheNextOneStartsAfterTheLastProject() throws Exception {
		List<UUID> projects = List.of(fixtures.newProject(), fixtures.newProject(), fixtures.newProject());
		for (int i = 0; i < projects.size(); i++) {
			jdbc.update("UPDATE project SET created_at = ? ::timestamptz WHERE id = ?",
					"2000-01-01T00:00:0" + (i + 1) + "Z", projects.get(i));
		}

		mvc.perform(get("/internal/projects/page").param("limit", "2")
						.param("afterCreatedAt", "2000-01-01T00:00:04Z").param("afterId", UUID.randomUUID().toString()))
				.andExpect(jsonPath("$.items[*].id").value(contains(projects.get(2).toString(), projects.get(1).toString())))
				.andExpect(jsonPath("$.hasNext").value(true));

		mvc.perform(get("/internal/projects/page").param("limit", "2")
						.param("afterCreatedAt", "2000-01-01T00:00:02Z").param("afterId", projects.get(1).toString()))
				.andExpect(jsonPath("$.items[*].id").value(contains(projects.get(0).toString())))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	void halfAPagePositionIsRejected() throws Exception {
		mvc.perform(get("/internal/projects/page").param("limit", "2").param("afterId", UUID.randomUUID().toString()))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	private ResultActions postJson(String path, String json) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String fixtureLines(String path, int from, int to) throws IOException {
		List<String> lines = Files.readAllLines(LocalDirectoryRepoFetcher.fixtureRoot().resolve(path));
		return String.join("\n", lines.subList(from - 1, to));
	}

}
