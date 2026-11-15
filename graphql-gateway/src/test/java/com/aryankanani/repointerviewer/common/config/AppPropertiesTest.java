package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.AppPropertiesConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * graphql-gateway's application.yml binds into the one record it turns on ({@link AppPropertiesConfiguration}, task
 * 4.1 step 4a): the Kafka consumers' retries. The services' records and their rules are tested in the services.
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
