package com.aryankanani.repointerviewer.api.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.TestTokens;
import com.aryankanani.repointerviewer.TestUsers;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 4.6 acceptance tests, persisted queries as prod runs them ({@code app.graphql.trusted-documents-only}; its own
 * context): over HTTP with a token, a listed operation runs by its hash alone, and any query not on the list, an
 * introspection query too, is refused with INVALID_INPUT before a service is called.
 */
@IntegrationTest
@TestPropertySource(properties = "app.graphql.trusted-documents-only=true")
class TrustedDocumentsHttpTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MockMvc mvc;

	private final FakeAnalysisApi analysis;

	TrustedDocumentsHttpTest(@Autowired WebApplicationContext context,
			@Autowired @Qualifier("springSecurityFilterChain") Filter security, @Autowired FakeAnalysisApi analysis) {
		this.mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
		this.analysis = analysis;
	}

	@BeforeEach
	void onlyTheTokenSaysWhoCalls() {
		SecurityContextHolder.clearContext();   // IntegrationTest's @WithMockUser set one on this thread
		analysis.reset();
	}

	/** No {@code query} in the body: Spring passes the hash on, and the text comes from the list. */
	@Test
	void aListedOperationRunsByItsHashAlone() throws Exception {
		UUID mine = analysis.readyProject();
		String file = new ClassPathResource("graphql-documents/Projects.graphql")
				.getContentAsString(StandardCharsets.UTF_8);

		send(Map.of("extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", sha256(file))),
				"variables", Map.of("first", 5)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(jsonPath("$.data.projects.edges[*].node.id").value(contains(mine.toString())));
	}

	@Test
	void aQueryNotOnTheListIsRefusedBeforeAnyServiceIsCalled() throws Exception {
		analysis.readyProject();

		send(Map.of("query", "{ projects(first: 100) { edges { node { id githubUrl } } } }"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.errors[0].message")
						.value("Only the operations on this API's list of trusted documents can run here."));
		assertThat(analysis.calls("page")).isZero();
	}

	/** No one can read the schema in prod: an introspection query is not on the list either. */
	@Test
	void introspectionIsRefused() throws Exception {
		send(Map.of("query", "{ __schema { types { name fields { name } } } }"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_INPUT"));
	}

	private ResultActions send(Map<String, Object> body) throws Exception {
		ResultActions sent = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.token(TestUsers.CANDIDATE)));
		// GraphQL over HTTP answers asynchronously, unless the result is complete at once (then MVC writes it directly)
		MvcResult result = sent.andReturn();
		return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : sent;
	}

	/** What {@code shasum -a 256 <file>} prints. */
	private static String sha256(String text) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
	}

}
