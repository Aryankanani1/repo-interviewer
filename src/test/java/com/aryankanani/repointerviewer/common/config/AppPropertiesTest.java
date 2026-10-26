package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Task 0.6 acceptance test. Binds the real application.yml into {@link AppProperties} in a tiny
 * context (no database, no Docker), and checks that invalid values stop startup.
 */
class AppPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application.yml
			.withUserConfiguration(PropertiesConfig.class);

	@Test
	void bindsApplicationYaml() {
		contextRunner.run(context -> {
			AppProperties app = context.getBean(AppProperties.class);

			assertThat(app.repo().maxFiles()).isEqualTo(1500);
			assertThat(app.repo().lsRemoteTimeout()).isEqualTo(Duration.ofSeconds(15));
			assertThat(app.repo().workDir()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "repo-interviewer"));
			assertThat(app.embedding().dimensions()).isEqualTo(768);
			assertThat(app.analysis().staleAfter()).isEqualTo(Duration.ofMinutes(15));
			assertThat(app.interview().prefetchEnabled()).isFalse();
			assertThat(app.llm().models().followUp()).isEqualTo("claude-sonnet-5");
			assertThat(app.llm().maxOutputTokens().followUp()).isEqualTo(1000);
		});
	}

	@Test
	void zeroMaxFilesFailsStartup() {
		contextRunner.withPropertyValues("app.repo.max-files=0").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("app.repo")
					.hasMessageContaining("maxFiles");
		});
	}

	@Test
	void minQuestionCountAboveDefaultFailsStartup() {
		contextRunner.withPropertyValues("app.interview.min-question-count=6").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("questionCountRangeValid");
		});
	}

	@Test
	void misspelledKeyFailsStartup() {
		contextRunner.withPropertyValues("app.repo.max-file=10").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("app.repo.max-file");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {
	}

}
