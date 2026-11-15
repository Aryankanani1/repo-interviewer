package com.aryankanani.repointerviewer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the two services this app calls (task 4.1) in integration tests. {@code @Primary} wins over the HTTP
 * client of the same type, which is created but never called (the clients' own tests are {@code AnalysisClientTest}
 * and {@code InterviewClientTest}). Part of {@link IntegrationTest}, so all tests share these beans: reset them in
 * {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

	/** Projects and the fixture repository's code without analysis-service. */
	@Bean
	@Primary
	FakeAnalysisApi fakeAnalysisApi() {
		return new FakeAnalysisApi();
	}

	/** Sessions, questions and answers without interview-service: each test adds the records it needs. */
	@Bean
	@Primary
	FakeInterviewApi fakeInterviewApi() {
		return new FakeInterviewApi();
	}

}
