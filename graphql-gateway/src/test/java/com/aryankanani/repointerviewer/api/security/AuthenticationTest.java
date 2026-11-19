package com.aryankanani.repointerviewer.api.security;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.TestTokens;
import com.aryankanani.repointerviewer.TestUsers;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Task 4.4 acceptance tests, 401: over HTTP through the real security filter chain ({@link SecurityConfiguration}),
 * a request to /graphql needs a token signed with the trusted key that has not expired; then GraphQL runs as the
 * token's {@code sub}. The tokens come from {@link TestTokens}, checked with its public key (TestDoublesConfiguration).
 */
@IntegrationTest
class AuthenticationTest {

	private static final String PROJECTS = """
			{"query": "{ projects(first: 5) { edges { node { id } } } }"}
			""";

	private final MockMvc mvc;

	private final FakeAnalysisApi analysis;

	AuthenticationTest(@Autowired WebApplicationContext context,
			@Autowired @Qualifier("springSecurityFilterChain") Filter security, @Autowired FakeAnalysisApi analysis) {
		this.mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
		this.analysis = analysis;
	}

	@BeforeEach
	void onlyTheTokenSaysWhoCalls() {
		SecurityContextHolder.clearContext();   // IntegrationTest's @WithMockUser set one on this thread
		analysis.reset();
	}

	@Test
	void withoutATokenItIs401() throws Exception {
		mvc.perform(graphQl(null))
				.andExpect(status().isUnauthorized())
				// Spring Security 7 adds where to find this resource's metadata (RFC 9728): only the scheme is ours
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
	}

	@Test
	void aTokenSignedWithAnotherKeyIs401() throws Exception {
		mvc.perform(graphQl(TestTokens.tokenSignedByAStranger(TestUsers.CANDIDATE)))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")));
	}

	@Test
	void anExpiredTokenIs401() throws Exception {
		mvc.perform(graphQl(TestTokens.expiredToken(TestUsers.CANDIDATE)))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")));
	}

	/** A valid token gets through, and its {@code sub} is the user: only that user's project is listed. */
	@Test
	void aValidTokenReachesGraphQlAsItsUser() throws Exception {
		UUID mine = analysis.readyProject();
		analysis.readyProjectOf(TestUsers.OTHER);

		ResultActions sent = mvc.perform(graphQl(TestTokens.token(TestUsers.CANDIDATE)));
		// GraphQL over HTTP answers asynchronously, unless the result is complete at once (then MVC writes it directly)
		MvcResult result = sent.andReturn();
		ResultActions answer = result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)) : sent;

		answer.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.projects.edges[*].node.id").value(contains(mine.toString())));
	}

	/** Kubernetes probes have no token. */
	@Test
	void healthNeedsNoToken() throws Exception {
		mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
	}

	private static MockHttpServletRequestBuilder graphQl(String token) {
		MockHttpServletRequestBuilder request = post("/graphql").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).content(PROJECTS);
		return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

}
