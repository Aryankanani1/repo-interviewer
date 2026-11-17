package com.aryankanani.repointerviewer.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.AppPropertiesConfiguration;
import com.aryankanani.repointerviewer.interview.InterviewProperties;
import com.aryankanani.repointerviewer.interview.RetrievalProperties;
import com.aryankanani.repointerviewer.llm.LlmProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Task 0.6 acceptance test, interview side (task 4.1 step 4a): interview-service's application.yml binds into the
 * records it turns on ({@link AppPropertiesConfiguration}) in a tiny context (no database, no Docker), invalid values
 * stop the start, and analysis-service's sections are not there.
 */
class AppPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())   // loads application.yml
			.withUserConfiguration(AppPropertiesConfiguration.class);

	@Test
	void bindsThisServicesApplicationYaml() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(InterviewProperties.class).defaultQuestionCount()).isEqualTo(5);
			assertThat(context.getBean(InterviewProperties.class).prefetchEnabled()).isTrue();
			assertThat(context.getBean(RetrievalProperties.class).maxContextTokens()).isEqualTo(6000);
			LlmProperties llm = context.getBean(LlmProperties.class);
			assertThat(llm.models().followUp()).isEqualTo("claude-sonnet-5");
			assertThat(llm.maxOutputTokens().followUp()).isEqualTo(2000);
			// The two caps that cut replies off in the first real run (task 1.15): 1500 and 1200. Question raised again
			// after the 1.17 eval measured a 3276-token reply.
			assertThat(llm.maxOutputTokens().question()).isEqualTo(7000);
			assertThat(llm.maxOutputTokens().evaluation()).isEqualTo(3000);
			assertThat(context.getBean(RolesProperties.class).interviewWorker()).isTrue();
			assertThat(context.getBean(RolesProperties.class).analysisWorker()).isFalse();
			ServiceCallProperties calls = context.getBean(ServiceCallProperties.class);   // task 4.2
			assertThat(calls.maxAttempts()).isEqualTo(3);
			assertThat(calls.openDuration()).isEqualTo(Duration.ofSeconds(10));
		});
	}

	@Test
	void analysisServicesSectionsAreNotHere() {
		contextRunner.run(context -> {
			assertThat(context.getEnvironment().containsProperty("app.repo.max-files")).isFalse();
			assertThat(context.getEnvironment().containsProperty("app.retrieval.top-k")).isFalse();
		});
	}

	@Test
	void minQuestionCountAboveDefaultFailsStartup() {
		contextRunner.withPropertyValues("app.interview.min-question-count=6").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("questionCountRangeValid");
		});
	}

	@Test
	void moreCallsPerSessionThanPerDayFailsStartup() {
		contextRunner.withPropertyValues("app.llm.max-calls-per-session=500").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("callLimitsValid");
		});
	}

	@Test
	void misspelledKeyFailsStartup() {
		contextRunner.withPropertyValues("app.interview.default-question-cont=4").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("app.interview.default-question-cont");
		});
	}

}
