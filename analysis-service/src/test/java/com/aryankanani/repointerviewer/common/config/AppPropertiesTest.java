package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.AppPropertiesConfiguration;
import com.aryankanani.repointerviewer.codebase.RepoProperties;
import com.aryankanani.repointerviewer.codebase.embed.EmbeddingProperties;
import com.aryankanani.repointerviewer.llm.LlmProperties;
import com.aryankanani.repointerviewer.project.AnalysisProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Task 0.6 acceptance test, analysis side (task 4.1 step 4a): analysis-service's application.yml binds into the
 * records it turns on ({@link AppPropertiesConfiguration}) in a tiny context (no database, no Docker), invalid values
 * stop the start, and interview-service's sections are not there.
 */
class AppPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application.yml
			.withUserConfiguration(AppPropertiesConfiguration.class);

	@Test
	void bindsThisServicesApplicationYaml() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			RepoProperties repo = context.getBean(RepoProperties.class);
			assertThat(repo.maxFiles()).isEqualTo(1500);
			assertThat(repo.lsRemoteTimeout()).isEqualTo(Duration.ofSeconds(15));
			assertThat(repo.workDir()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "analysis-service"));
			assertThat(context.getBean(AnalysisProperties.class).staleAfter()).isEqualTo(Duration.ofMinutes(15));
			assertThat(context.getBean(EmbeddingProperties.class).dimensions()).isEqualTo(768);
			assertThat(context.getBean(LlmProperties.class).models().summary()).isEqualTo("claude-haiku-4-5");
			assertThat(context.getBean(RolesProperties.class).analysisWorker()).isTrue();
			assertThat(context.getBean(RolesProperties.class).interviewWorker()).isFalse();
		});
	}

	@Test
	void interviewServicesSectionsAreNotHere() {
		contextRunner.run(context -> {
			assertThat(context.getEnvironment().containsProperty("app.interview.default-question-count")).isFalse();
			assertThat(context.getEnvironment().containsProperty("app.retrieval.max-context-tokens")).isFalse();
		});
	}

	@Test
	void zeroMaxFilesFailsStartup() {
		contextRunner.withPropertyValues("app.repo.max-files=0").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("app.repo")
					.hasMessageContaining("maxFiles");
		});
	}

	@Test
	void misspelledKeyFailsStartup() {
		contextRunner.withPropertyValues("app.repo.max-file=10").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("app.repo.max-file");
		});
	}

}
