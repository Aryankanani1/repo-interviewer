package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Task 4.2: the OpenAPI docs of the internal API, generated from the controller, describe all of AnalysisApi. */
@IntegrationTest
class OpenApiDocsTest {

	private final MockMvc mvc;

	private final JsonMapper jsonMapper;

	OpenApiDocsTest(@Autowired WebApplicationContext context, @Autowired JsonMapper jsonMapper) {
		this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
		this.jsonMapper = jsonMapper;
	}

	@Test
	void theDocsDescribeEveryMethodOfAnalysisApiAndNothingElse() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode document = jsonMapper.readTree(body);

		assertThat(OpenApiDocs.missing(document, AnalysisApi.class)).isEmpty();
		assertThat(document.path("paths").propertyNames()).allMatch(path -> path.startsWith("/internal/"));
		assertThat(document.at("/info/title").asString()).isEqualTo("analysis-service internal API");
	}

	@Test
	void swaggerUiIsOnlyForDev() throws Exception {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
	}

}
