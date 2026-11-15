package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.core.env.Environment;

/** analysis-service's environments (DECISIONS.md D-015) over its application.yml, task 4.1. */
class EnvironmentProfilesTest {

	/** What a stage or prod deployment of this service provides as environment variables. */
	private static final String[] DEPLOYMENT_VARIABLES = { "DB_URL=jdbc:postgresql://db:5432/repo_interviewer",
			"DB_USER=analysis_svc", "DB_PASSWORD=from-a-secret", "OLLAMA_BASE_URL=http://ollama:11434" };

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application*.yml
			.withUserConfiguration(AppPropertiesTest.PropertiesConfig.class);

	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "stage", "prod" })
	void everyEnvironmentLoadsOnTopOfTheDefaults(String environment) {
		runDeployed(environment, context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AppProperties.class).repo().maxFiles()).isEqualTo(1500);
		});
	}

	/** Its own user, schema and port: never the app's (the Compose admin user is DB_USER in .env). */
	@Test
	void devUsesTheLocalInfrastructureAsItsOwnUser() {
		run("dev", context -> {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:postgresql://localhost:");
			assertThat(env.getProperty("spring.datasource.username")).isEqualTo("analysis_svc");
			assertThat(env.getProperty("spring.ai.ollama.base-url")).startsWith("http://localhost:");
			assertThat(env.getProperty("server.port")).isEqualTo("8082");
		});
	}

	@Test
	void everyEnvironmentUsesTheSchemaAnalysis() {
		run("prod", context -> {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("analysis");
			assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("analysis");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "stage", "prod" })
	void stageAndProdTakeConnectionsFromEnvironmentVariables(String environment) {
		runDeployed(environment, context -> {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/repo_interviewer");
			assertThat(env.getProperty("spring.datasource.username")).isEqualTo("analysis_svc");
			assertThat(env.getProperty("spring.ai.ollama.base-url")).isEqualTo("http://ollama:11434");
		});
	}

	/** 1/5 of the old caps; the app has the rest (D-026). */
	@Test
	void devAndStageCapDailyLlmCallsBelowProd() {
		run("dev", context -> assertThat(context.getBean(AppProperties.class).llm().maxCallsPerDay()).isEqualTo(12));
		run("stage", context -> assertThat(context.getBean(AppProperties.class).llm().maxCallsPerDay()).isEqualTo(20));
		run("prod", context -> assertThat(context.getBean(AppProperties.class).llm().maxCallsPerDay()).isEqualTo(60));
	}

	@Test
	void withoutAnEnvironmentThereIsNoDatabaseToConnectTo() {
		contextRunner.run(context -> assertThatThrownBy(
				() -> context.getEnvironment().getProperty("spring.datasource.url"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("DB_URL"));
	}

	private void run(String environment, ContextConsumer<AssertableApplicationContext> assertions) {
		contextRunner.withPropertyValues("spring.profiles.active=" + environment).run(assertions);
	}

	private void runDeployed(String environment, ContextConsumer<AssertableApplicationContext> assertions) {
		contextRunner.withPropertyValues("spring.profiles.active=" + environment)
				.withPropertyValues(DEPLOYMENT_VARIABLES)
				.run(assertions);
	}

}
