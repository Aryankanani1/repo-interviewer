package com.aryankanani.repointerviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * analysis-service (task 4.1, DECISIONS.md D-026): the {@code project} and {@code codebase} modules, with their own
 * schema, behind {@code AnalysisApi}. Scans the whole base package, which in this service holds only those modules
 * and the libraries ({@code common}, {@code llm}, {@code contracts}, {@code outbox}). Its {@code app.*} settings:
 * {@link AppPropertiesConfiguration}.
 */
@SpringBootApplication
@EnableScheduling   // only OutboxRelay is @Scheduled, and only with the kafka profile (task 2.2)
public class AnalysisServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalysisServiceApplication.class, args);
	}

}
