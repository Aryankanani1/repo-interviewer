package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.AppPropertiesConfiguration;
import com.aryankanani.repointerviewer.api.ratelimit.RateLimitProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * graphql-gateway's application.yml binds into the records it turns on ({@link AppPropertiesConfiguration}, task
 * 4.1 step 4a): the Kafka consumers' retries, and the calls to the services (task 4.2). The services' records and
 * their rules are tested in the services.
 */
class AppPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application.yml
			.withUserConfiguration(AppPropertiesConfiguration.class);

	@Test
	void bindsTheGatewaysApplicationYaml() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			ConsumerRetryProperties retry = context.getBean(ConsumerRetryProperties.class);
			assertThat(retry.maxRetries()).isEqualTo(3);
			assertThat(retry.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
			ServiceCallProperties calls = context.getBean(ServiceCallProperties.class);   // task 4.2
			assertThat(calls.maxAttempts()).isEqualTo(3);
			assertThat(calls.openDuration()).isEqualTo(Duration.ofSeconds(10));
			RateLimitProperties limits = context.getBean(RateLimitProperties.class);   // task 4.4
			assertThat(limits.submitProject().capacity()).isEqualTo(3);
			assertThat(limits.startInterview().refillEvery()).isEqualTo(Duration.ofMinutes(10));
		});
	}

	@Test
	void misspelledKeyFailsStartup() {
		contextRunner.withPropertyValues("app.consumer-retry.max-retry=10").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("app.consumer-retry.max-retry");
		});
	}

}
