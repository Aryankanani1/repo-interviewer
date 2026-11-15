package com.aryankanani.repointerviewer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces ports that would reach the outside world (analysis-service, Claude) in integration tests.
 * {@code @Primary} wins over the production bean of the same type. Part of {@link IntegrationTest}, so all
 * tests share these beans: reset them in {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

	/**
	 * Static: a BeanPostProcessor must exist before the beans it changes (here the DataSource), so Spring creates it
	 * without creating this configuration first.
	 */
	@Bean
	static SqlStatementCounter sqlStatementCounter() {
		return new SqlStatementCounter();
	}

	/**
	 * Wins over the HTTP client for analysis-service (task 4.1), which is created but never called: projects and the
	 * fixture repository's code without another service (the client's own test is the app's
	 * {@code AnalysisClientTest}, the same configuration).
	 */
	@Bean
	@Primary
	FakeAnalysisApi fakeAnalysisApi() {
		return new FakeAnalysisApi();
	}

	/** Wins over SpringAiLlmGateway, which is created but never called (its own tests build one by hand). */
	@Bean
	@Primary
	FakeLlmGateway fakeLlmGateway() {
		return new FakeLlmGateway();
	}

}
