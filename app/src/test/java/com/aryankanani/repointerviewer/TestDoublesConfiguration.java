package com.aryankanani.repointerviewer;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces ports that would reach the outside world (GitHub, the analysis pipeline, Ollama, Claude) in integration
 * tests.
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

	@Bean
	@Primary
	LocalDirectoryRepoFetcher localDirectoryRepoFetcher() {
		return new LocalDirectoryRepoFetcher();
	}

	@Bean
	@Primary
	RecordingAnalysisPipeline recordingAnalysisPipeline(DataSource dataSource) {
		return new RecordingAnalysisPipeline(dataSource);
	}

	/** Wins over Spring AI's auto-configured OllamaEmbeddingModel, which is created but never called. */
	@Bean
	@Primary
	FakeEmbeddingModel fakeEmbeddingModel() {
		return new FakeEmbeddingModel();
	}

	/** Wins over SpringAiLlmGateway, which is created but never called (its own tests build one by hand). */
	@Bean
	@Primary
	FakeLlmGateway fakeLlmGateway() {
		return new FakeLlmGateway();
	}

}
