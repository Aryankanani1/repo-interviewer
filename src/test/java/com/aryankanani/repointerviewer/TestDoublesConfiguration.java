package com.aryankanani.repointerviewer;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces ports that would reach the outside world (GitHub, the analysis pipeline) in integration tests.
 * {@code @Primary} wins over the production bean of the same type. Part of {@link IntegrationTest}, so all
 * tests share these beans: reset them in {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

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

}
