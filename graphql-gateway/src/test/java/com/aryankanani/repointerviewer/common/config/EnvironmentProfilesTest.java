package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.AppPropertiesConfiguration;
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
 * graphql-gateway's environment files (DECISIONS.md D-015). Each loads on top of application.yml with valid
 * {@code app.*} settings ({@link AppPropertiesConfiguration}), and sets what makes it different. Tiny context: no
 * Docker. Since task 4.1 step 4a the gateway has no database and no roles.
 *
 * <p>The real environment variables and a local {@code .env} file are visible here too, so these tests only
 * pin values that neither of them is expected to change.
 */
class EnvironmentProfilesTest {

	/** What a stage or prod deployment of the gateway provides as environment variables. */
	private static final String[] DEPLOYMENT_VARIABLES = { "ANALYSIS_BASE_URL=http://analysis-service:8080",
			"INTERVIEW_BASE_URL=http://interview-service:8080" };

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application*.yml
			.withUserConfiguration(AppPropertiesConfiguration.class);

	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "stage", "prod" })
	void everyEnvironmentLoadsOnTopOfTheDefaults(String environment) {
		run(environment, context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(environment);
			// Not set in any environment file, so it comes from application.yml.
			assertThat(context.getBean(ConsumerRetryProperties.class).maxRetries()).isEqualTo(3);
		});
	}

	@ParameterizedTest(name = "{0}: GraphiQL {1}")
	@CsvSource({ "dev, true", "test, false", "stage, true", "prod, false" })
	void graphiqlIsOnInDevAndStageOnly(String environment, boolean enabled) {
		run(environment, context -> assertThat(
				context.getEnvironment().getProperty("spring.graphql.graphiql.enabled", Boolean.class)).isEqualTo(enabled));
	}

	/** Task 4.1 step 4a: no database, no roles, no other service's settings in any environment. */
	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "stage", "prod" })
	void noDatabaseRolesOrServiceSettings(String environment) {
		runDeployed(environment, context -> {
			Environment env = context.getEnvironment();
			assertThat(env.containsProperty("spring.datasource.url")).isFalse();
			assertThat(env.containsProperty("app.roles.api")).isFalse();
			assertThat(env.containsProperty("app.llm.max-calls-per-day")).isFalse();
			assertThat(env.containsProperty("app.repo.max-files")).isFalse();
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
			assertThat(env.getProperty("spring.http.serviceclient.analysis.base-url")).isEqualTo("http://localhost:8082");
			assertThat(env.getProperty("spring.http.serviceclient.interview.base-url"))
					.isEqualTo("http://localhost:8084");
			assertThat(env.getProperty("logging.level.com.aryankanani.repointerviewer")).isEqualTo("DEBUG");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "stage", "prod" })
	void stageAndProdTakeConnectionsFromEnvironmentVariables(String environment) {
		runDeployed(environment, context -> {
			Environment env = context.getEnvironment();
			assertThat(env.getProperty("spring.http.serviceclient.analysis.base-url"))
					.isEqualTo("http://analysis-service:8080");
			assertThat(env.getProperty("spring.http.serviceclient.interview.base-url"))
					.isEqualTo("http://interview-service:8080");
		});
	}

	/**
	 * {@code submitAnswer} grades the answer and writes the next question in one request: 25-43 s in the first
	 * real run (task 1.15), past Tomcat's 30 s default, which answered HTTP 503 while the work went on. Since task
	 * 4.1 interview-service does that work, so its client waits as long as the request itself.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "dev", "test", "stage", "prod" })
	void requestsMayRunLongerThanTomcatsDefaultAsyncTimeout(String environment) {
		// Bound the way Boot binds spring.mvc.* (Environment.getProperty cannot read "120s" as a Duration).
		run(environment, context -> {
			Binder binder = Binder.get(context.getEnvironment());
			assertThat(binder.bind("spring.mvc.async.request-timeout", Duration.class).orElse(null))
					.isEqualTo(Duration.ofSeconds(120));
			assertThat(binder.bind("spring.http.serviceclient.interview.read-timeout", Duration.class).orElse(null))
					.isEqualTo(Duration.ofSeconds(120));
		});
	}

	@Test
	void prodTurnsOffTheSchemaReport() {
		run("prod", context -> assertThat(context.getEnvironment()
				.getProperty("spring.graphql.schema.inspection.enabled", Boolean.class)).isFalse());
		run("dev", context -> assertThat(context.getEnvironment()
				.getProperty("spring.graphql.schema.inspection.enabled", Boolean.class)).isTrue());
	}

	@Test
	void withoutAnEnvironmentThereAreNoServicesToCall() {
		contextRunner.run(context -> {
			assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
			// application.yml has no localhost fallback: the gateway cannot quietly call the wrong services.
			Environment env = context.getEnvironment();
			assertThatThrownBy(() -> env.getProperty("spring.http.serviceclient.analysis.base-url"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("ANALYSIS_BASE_URL");
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
