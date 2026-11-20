package com.aryankanani.repointerviewer.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aryankanani.repointerviewer.IntegrationTest;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Task 5.2: the web page and the documents it loads open without a token (its GraphQL requests carry the token
 * pasted into it), and every operation it sends is on the list of trusted documents, so it also works in prod.
 */
@IntegrationTest
class WebPageTest {

	/** How the page sends an operation: {@code gql("StartInterview", ...)}. */
	private static final Pattern OPERATION = Pattern.compile("gql\\(\"(\\w+)\"");

	private final MockMvc mvc;

	WebPageTest(@Autowired WebApplicationContext context,
			@Autowired @Qualifier("springSecurityFilterChain") Filter security) {
		this.mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
	}

	@BeforeEach
	void noUser() {
		SecurityContextHolder.clearContext();   // IntegrationTest's @WithMockUser set one on this thread
	}

	@Test
	void thePageOpensWithoutAToken() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
		mvc.perform(get("/index.html")).andExpect(status().isOk())
				.andExpect(content().string(containsString("<title>Repo Interviewer</title>")));
	}

	@Test
	void aTrustedDocumentOpensWithoutAToken() throws Exception {
		mvc.perform(get("/documents/StartInterview.graphql")).andExpect(status().isOk())
				.andExpect(content().string(document("StartInterview")));
	}

	@Test
	void everyOperationThePageSendsIsATrustedDocument() throws Exception {
		Set<String> sent = new TreeSet<>();
		Matcher matcher = OPERATION.matcher(new ClassPathResource("static/index.html")
				.getContentAsString(StandardCharsets.UTF_8));
		while (matcher.find()) {
			sent.add(matcher.group(1));
		}

		assertThat(sent).contains("Projects", "Project", "SubmitProject", "StartInterview", "SubmitAnswer",
				"EndInterview", "InterviewSession");
		assertThat(sent).allSatisfy(name -> assertThat(
				new ClassPathResource("graphql-documents/" + name + ".graphql").exists()).as(name).isTrue());
	}

	private static String document(String name) throws Exception {
		return new ClassPathResource("graphql-documents/" + name + ".graphql").getContentAsString(StandardCharsets.UTF_8);
	}

}
