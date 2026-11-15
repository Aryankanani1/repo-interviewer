package com.aryankanani.repointerviewer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.chunk.TestFiles;
import com.aryankanani.repointerviewer.codebase.map.RepoFile;
import com.aryankanani.repointerviewer.codebase.map.RepoMapBuilder;
import com.aryankanani.repointerviewer.codebase.scan.FileClassifier;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * Task 1.8 manual smoke call: one real SUMMARY call (Claude Haiku) on the fixture repository, through the real
 * gateway, budget and ledger. Costs money, so it is tagged {@code eval}: skipped by {@code ./mvnw verify}, run
 * alone with {@code ./mvnw verify -Pevals} (ask Aryan first). Needs {@code ANTHROPIC_API_KEY} in {@code .env}.
 *
 * <p>It also checks that the summary model accepts Anthropic's native structured output ({@code outputSchema}).
 */
@Tag("eval")
@IntegrationTest
@TestPropertySource(properties = "spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}")
class LlmSmokeEvalTest {

	private final SpringAiLlmGateway gateway;

	private final RepoMapBuilder repoMapBuilder;

	private final LlmCallRepository calls;

	private final FixtureIndexer fixture;

	private final Environment environment;

	LlmSmokeEvalTest(@Autowired SpringAiLlmGateway gateway, @Autowired RepoMapBuilder repoMapBuilder,
			@Autowired LlmCallRepository calls, @Autowired FixtureIndexer fixture, @Autowired Environment environment) {
		this.gateway = gateway;
		this.repoMapBuilder = repoMapBuilder;
		this.calls = calls;
		this.fixture = fixture;
		this.environment = environment;
	}

	@Test
	void summarizesTheFixtureRepositoryWithTheRealModel() {
		// Without a key, Spring would send the literal text "${ANTHROPIC_API_KEY}" and get a 401. Stop earlier.
		assertThat(environment.getProperty("ANTHROPIC_API_KEY")).as("ANTHROPIC_API_KEY in .env (see .env.example)")
				.isNotBlank();
		FileClassifier classifier = new FileClassifier();
		List<RepoFile> files = LocalDirectoryRepoFetcher.FIXTURE_FILES.stream()
				.map(path -> new RepoFile(TestFiles.fixture(path), classifier.classify(TestFiles.scanned(path))))
				.toList();
		Map<String, Object> variables = new HashMap<>();
		variables.put("repoName", "example/shop");
		variables.put("goal", "Learn Spring Boot by building a small order service");
		variables.put("domain", "e-commerce");
		variables.put("repoMap", repoMapBuilder.build(files));
		variables.put("buildFiles", TestFiles.fixture("pom.xml").content());
		variables.put("readme", TestFiles.fixture("README.md").content());
		UUID project = fixture.newProject();

		SummaryOutput summary = gateway.call(
				new LlmRequest<>(LlmPurpose.SUMMARY, variables, SummaryOutput.class, project, null));

		LlmCall row = calls.findAll().stream().filter(call -> project.equals(call.getProjectId())).findFirst()
				.orElseThrow();
		System.out.printf("%nSmoke call: model=%s promptVersion=%s attempt=%d latencyMs=%d inputTokens=%d "
				+ "outputTokens=%d%n%s%n%n", row.getModel(), row.getPromptVersion(), row.getAttempt(),
				row.getLatencyMs(), row.getInputTokens(), row.getOutputTokens(), summary);
		assertThat(row.isSuccess()).isTrue();
		assertThat(row.getInputTokens()).isPositive();
		assertThat(summary.oneLiner()).isNotBlank();
		assertThat(summary.modules()).isNotEmpty();
	}

}
