package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * analysis-service's application.yml binds into {@link AppProperties} (task 4.1; the record's own rules are tested
 * in the app). The record still needs every section, the interview's too (DECISIONS.md D-026).
 */
class AppPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application.yml
			.withUserConfiguration(PropertiesConfig.class);

	@Test
	void bindsThisServicesApplicationYaml() {
		contextRunner.run(context -> {
			AppProperties app = context.getBean(AppProperties.class);
			assertThat(app.repo().workDir()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "analysis-service"));
			assertThat(app.embedding().dimensions()).isEqualTo(768);
			assertThat(app.llm().models().summary()).isEqualTo("claude-haiku-4-5");
			assertThat(app.roles().analysisWorker()).isTrue();
			assertThat(app.roles().interviewWorker()).isFalse();
		});
	}

	@Test
	void misspelledKeyFailsStartup() {
		contextRunner.withPropertyValues("app.repo.max-file=10").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("app.repo.max-file");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class PropertiesConfig {
	}

}
