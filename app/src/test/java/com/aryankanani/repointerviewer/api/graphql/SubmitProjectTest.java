package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * {@code Mutation.submitProject} shows analysis-service's answer (task 4.1). What a submit does (URL rules, one
 * project per commit, the insert race, cleaning the context) is tested there: analysis-service's
 * {@code SubmitProjectTest}.
 */
@IntegrationTest
class SubmitProjectTest {

	private static final String SUBMIT = """
			mutation ($input: SubmitProjectInput!) {
			  submitProject(input: $input) {
			    project { id name githubUrl commitSha status failureReason context { goal domain } }
			    errors { code message field }
			  }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi analysis;

	SubmitProjectTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
	}

	@BeforeEach
	void resetState() {
		analysis.reset();
	}

	@Test
	void theSubmittedProjectIsShownWithItsNameAndContext() {
		GraphQlTester.Response response = submit(Map.of("githubUrl", "https://github.com/acme/shop",
				"goal", "Learn Kafka"));

		response.path("submitProject.errors").entityList(Object.class).hasSize(0);
		response.path("submitProject.project.name").entity(String.class).isEqualTo("acme/shop");
		response.path("submitProject.project.githubUrl").entity(String.class).isEqualTo("https://github.com/acme/shop");
		response.path("submitProject.project.status").entity(String.class).isEqualTo("SUBMITTED");
		response.path("submitProject.project.context.goal").entity(String.class).isEqualTo("Learn Kafka");
		assertThat(analysis.calls("submit")).isEqualTo(1);
	}

	@Test
	void anAnalysisServiceErrorIsAPayloadErrorOnItsInputField() {
		analysis.failNextCall(new DomainException(ErrorCode.REPO_NOT_FOUND,
				"No public GitHub repository at that URL.", "githubUrl"));

		GraphQlTester.Response response = submit(Map.of("githubUrl", "https://github.com/acme/private-repo"));

		response.path("submitProject.project").valueIsNull();
		response.path("submitProject.errors[0].code").entity(String.class).isEqualTo("REPO_NOT_FOUND");
		response.path("submitProject.errors[0].message").entity(String.class)
				.isEqualTo("No public GitHub repository at that URL.");
		response.path("submitProject.errors[0].field").entityList(String.class).containsExactly("input", "githubUrl");
	}

	private GraphQlTester.Response submit(Map<String, Object> input) {
		return graphQlTester.document(SUBMIT).variable("input", input).execute();
	}

}
