package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.common.error.ModelUnavailableException;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkSearchRequest;
import com.aryankanani.repointerviewer.contracts.analysis.TopicQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * The client half of the analysis contract (task 4.1; the server half is analysis-service's
 * {@code AnalysisApiControllerTest}). A small context with Boot's HTTP client auto-configuration and
 * {@link AnalysisClientConfiguration}, as in the app (with its {@code ServiceCallGuard}, task 4.2), and a
 * {@link MockRestServiceServer} in place of the network.
 */
class AnalysisClientTest {

	private static final String BASE = "http://analysis.test";

	private static final String PROBLEM = """
			{"type": "about:blank", "title": "%s", "status": %d, "detail": "%s", "code": "%s"%s}
			""";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // application.yml: app.service-calls
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
					ImperativeHttpClientAutoConfiguration.class, HttpServiceClientPropertiesAutoConfiguration.class,
					RestClientAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
			.withUserConfiguration(AnalysisClientConfiguration.class, AppPropertiesConfiguration.class,
					MockServerConfiguration.class)
			.withPropertyValues("spring.http.serviceclient.analysis.base-url=" + BASE);

	@Test
	void aCallIsARequestToTheConfiguredServiceWithTheRecordsAsJson() {
		UUID project = UUID.randomUUID();
		run((analysis, server) -> {
			server.expect(requestTo(BASE + "/internal/projects/" + project + "/context"))
					.andExpect(method(HttpMethod.GET))
					.andRespond(withSuccess("""
							{"id": "%s", "status": "READY", "name": "acme/shop", "repoMap": "src/"}
							""".formatted(project), MediaType.APPLICATION_JSON));
			server.expect(requestTo(BASE + "/internal/projects/" + project + "/chunks/search"))
					.andExpect(method(HttpMethod.POST))
					.andExpect(content().json("""
							{"query": {"text": "Refunds", "targetPaths": [], "targetSymbols": ["OrderService"],
							           "includeTests": false},
							 "maxTokens": 6000}
							"""))
					.andRespond(withSuccess("""
							{"chunks": [{"path": "A.java", "startLine": 1, "endLine": 2, "content": "class A {}",
							             "tokenEstimate": 4, "similarity": 1.0}], "totalTokens": 4}
							""", MediaType.APPLICATION_JSON));

			assertThat(analysis.context(project).status()).isEqualTo(AnalysisStatus.READY);
			assertThat(analysis.searchChunks(project, new ChunkSearchRequest(
					new TopicQuery("Refunds", List.of(), List.of("OrderService"), false), 6000)).totalTokens())
					.isEqualTo(4);
			server.verify();
		});
	}

	/** The instant as ISO-8601, its colons encoded; analysis-service's MVC decodes them back. */
	@Test
	void aPagePositionIsSentAsQueryParameters() {
		UUID after = UUID.randomUUID();
		run((analysis, server) -> {
			server.expect(requestTo(BASE + "/internal/projects/page?userId=user-a&limit=2"
					+ "&afterCreatedAt=2026-11-07T10%3A00%3A00Z&afterId=" + after))
					.andRespond(withSuccess("""
							{"items": [], "hasNext": false}
							""", MediaType.APPLICATION_JSON));

			assertThat(analysis.page(TestUsers.CANDIDATE, 2, Instant.parse("2026-11-07T10:00:00Z"), after).items())
					.isEmpty();
			server.verify();
		});
	}

	@Test
	void aProblemDetailIsTheSameDomainExceptionAsOnTheOtherSide() {
		UUID project = UUID.randomUUID();
		run((analysis, server) -> {
			server.expect(requestTo(BASE + "/internal/projects/" + project + "/reanalyze"))
					.andRespond(problem(HttpStatus.NOT_FOUND, "Project not found.", "NOT_FOUND", "projectId"));

			assertThatThrownBy(() -> analysis.reanalyze(project)).isInstanceOfSatisfying(DomainException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
				assertThat(ex.getMessage()).isEqualTo("Project not found.");
				assertThat(ex.field()).contains("projectId");
			});
		});
	}

	/** So an interview worker retries it, like its own model being down (task 3.6). */
	@Test
	void llmUnavailableIsAModelUnavailableException() {
		UUID project = UUID.randomUUID();
		run((analysis, server) -> {
			server.expect(requestTo(BASE + "/internal/projects/" + project + "/code-names"))
					.andRespond(problem(HttpStatus.SERVICE_UNAVAILABLE, FakeAnalysisApi.EMBEDDING_UNAVAILABLE,
							"LLM_UNAVAILABLE", null));

			assertThatThrownBy(() -> analysis.codeNames(project)).isInstanceOf(ModelUnavailableException.class)
					.hasMessage(FakeAnalysisApi.EMBEDDING_UNAVAILABLE);
		});
	}

	@Test
	void anErrorThatIsNotAProblemDetailIsSpringsUsualException() {
		UUID project = UUID.randomUUID();
		run((analysis, server) -> {
			server.expect(requestTo(BASE + "/internal/projects/" + project + "/code-names"))
					.andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
							.body("<html>502 from a proxy</html>"));

			assertThatThrownBy(() -> analysis.codeNames(project)).isInstanceOf(HttpServerErrorException.class);
		});
	}

	private void run(BiConsumer<AnalysisApi, MockRestServiceServer> test) {
		contextRunner.run(context -> test.accept(context.getBean(AnalysisApi.class),
				context.getBean(MockServerConfiguration.class).server));
	}

	private static org.springframework.test.web.client.ResponseCreator problem(HttpStatus status, String detail,
			String code, String field) {
		String json = PROBLEM.formatted(status.getReasonPhrase(), status.value(), detail, code,
				field == null ? "" : ", \"field\": \"" + field + "\"");
		return withStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(json);
	}

	/** Binds a mock server to the analysis group's RestClient, after the app's own configuration of it. */
	@Configuration(proxyBeanMethods = false)
	static class MockServerConfiguration {

		MockRestServiceServer server;

		@Bean
		RestClientHttpServiceGroupConfigurer mockAnalysisServer() {
			return groups -> groups.filterByName(AnalysisClientConfiguration.GROUP)
					.forEachClient((group, builder) -> server = MockRestServiceServer.bindTo(builder).build());
		}

	}

}
