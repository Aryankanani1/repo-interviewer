package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.core.env.Environment;

/**
 * The environment files (DECISIONS.md D-015). Each loads on top of application.yml with valid
 * {@link AppProperties}, and sets what makes it different. Tiny context: no database, no Docker.
 *
 * <p>The real environment variables and a local {@code .env} file are visible here too, so these tests only
 * pin values that neither of them is expected to change.
 */
class EnvironmentProfilesTest {

	/** What a stage or prod deployment provides as environment variables. */
	private static final String[] DEPLOYMENT_VARIABLES = { "DB_URL=jdbc:postgresql://db:5432/repo_interviewer",
			"DB_USER=ri", "DB_PASSWORD=from-a-secret", "OLLAMA_BASE_URL=http://ollama:11434" };

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application*.yml
			.withUserConfiguration(AppPropertiesTest.PropertiesConfig.class);

	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "stage", "prod" })
	void everyEnvironmentLoadsOnTopOfTheDefaults(String environment) {
		run(environment, context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(environment);
			// Not set in any environment file, so it comes from application.yml.
			assertThat(context.getBean(AppProperties.class).repo().maxFiles()).isEqualTo(1500);
		});
	}

	@ParameterizedTest(name = "{0}: GraphiQL {1}")
	@CsvSource({ "dev, true", "test, false", "stage, true", "prod, false" })
	void graphiqlIsOnInDevAndStageOnly(String environment, boolean enabled) {
		run(environment, context -> assertThat(
				context.getEnvironment().getProperty("spring.graphql.graphiql.enabled", Boolean.class)).isEqualTo(enabled));
	}

	/** Role profiles (task 3.3): each turns on only its own role; without one, one JVM does everything. */
	@ParameterizedTest(name = "{0}: api {1}, analysis {2}, interview {3}")
	@CsvSource({ "stage, true, true, true", "'stage,kafka,api', true, false, false",
			"'stage,kafka,analysis-worker', false, true, false", "'stage,kafka,interview-worker', false, false, true" })
	void aRoleProfileKeepsOnlyItsOwnRole(String profiles, boolean api, boolean analysis, boolean interview) {
		runDeployed(profiles, context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AppProperties.class).roles())
					.isEqualTo(new AppProperties.Roles(api, analysis, interview));
		});
	}

	@Test
	void graphiqlIsOffByDefault() {
		contextRunner.run(context -> assertThat(
				context.getEnvironment().getProperty("spring.graphql.graphiql.enabled", Boolean.class)).isFalse());
	}

	@Test
	void devUsesTheLocalInfrastructure() {
		run("dev", context -> {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:postgresql://localhost:");
			assertThat(env.getProperty("spring.ai.ollama.base-url")).startsWith("http://localhost:");
			assertThat(env.getProperty("logging.level.com.aryankanani.repointerviewer")).isEqualTo("DEBUG");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "stage", "prod" })
	void stageAndProdTakeConnectionsFromEnvironmentVariables(String environment) {
		runDeployed(environment, context -> {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/repo_interviewer");
			assertThat(env.getProperty("spring.datasource.password")).isEqualTo("from-a-secret");
			assertThat(env.getProperty("spring.ai.ollama.base-url")).isEqualTo("http://ollama:11434");
		});
	}

	@Test
	void devAndStageCapDailyLlmCallsBelowProd() {
		run("dev", context -> assertThat(context.getBean(AppProperties.class).llm().maxCallsPerDay()).isEqualTo(60));
		run("stage", context -> assertThat(context.getBean(AppProperties.class).llm().maxCallsPerDay()).isEqualTo(100));
		run("prod", context -> assertThat(context.getBean(AppProperties.class).llm().maxCallsPerDay()).isEqualTo(300));
	}

	/**
	 * {@code submitAnswer} grades the answer and writes the next question in one request: 25-43 s in the first
	 * real run (task 1.15), past Tomcat's 30 s default, which answered HTTP 503 while the work went on.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "stage", "prod" })
	void requestsMayRunLongerThanTomcatsDefaultAsyncTimeout(String environment) {
		// Bound the way Boot binds spring.mvc.* (Environment.getProperty cannot read "120s" as a Duration).
		run(environment, context -> assertThat(Binder.get(context.getEnvironment())
				.bind("spring.mvc.async.request-timeout", Duration.class).orElse(null))
				.isEqualTo(Duration.ofSeconds(120)));
	}

	@Test
	void prodTurnsOffTheSchemaReport() {
		run("prod", context -> assertThat(context.getEnvironment()
				.getProperty("spring.graphql.schema.inspection.enabled", Boolean.class)).isFalse());
		run("dev", context -> assertThat(context.getEnvironment()
				.getProperty("spring.graphql.schema.inspection.enabled", Boolean.class)).isTrue());
	}

	@Test
	void withoutAnEnvironmentThereIsNoDatabaseToConnectTo() {
		contextRunner.run(context -> {
			assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
			// application.yml has no localhost fallback: the app cannot quietly start against the wrong database.
			assertThatThrownBy(() -> context.getEnvironment().getProperty("spring.datasource.url"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("DB_URL");
		});
	}

	@Test
	void guardStopsStartupWithAClearMessageOnlyWhenNoEnvironmentIsChosen() {
		ApplicationContextRunner withGuard = contextRunner.withUserConfiguration(NoEnvironmentGuard.class);

		withGuard.run(context -> {
			assertThat(context).hasFailed();
			// Thrown before any bean exists, so it is the failure itself, not wrapped in a bean-creation error.
			assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
					.hasMessage(NoEnvironmentGuard.MESSAGE);
		});
		withGuard.withPropertyValues("spring.profiles.active=dev").run(context -> assertThat(context).hasNotFailed());
	}

	private void run(String environment, ContextConsumer<AssertableApplicationContext> assertions) {
		contextRunner.withPropertyValues("spring.profiles.active=" + environment).run(assertions);
	}

	/** Like {@link #run}, plus the variables a deployment provides. */
	private void runDeployed(String environment, ContextConsumer<AssertableApplicationContext> assertions) {
		contextRunner.withPropertyValues("spring.profiles.active=" + environment)
				.withPropertyValues(DEPLOYMENT_VARIABLES)
				.run(assertions);
	}

}
