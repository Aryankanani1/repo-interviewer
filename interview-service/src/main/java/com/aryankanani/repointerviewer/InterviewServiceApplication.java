package com.aryankanani.repointerviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * interview-service (task 4.1, DECISIONS.md D-026): the {@code interview} module, with its own schema, behind
 * {@code InterviewApi}. Scans the whole base package, which in this service holds only that module and the libraries
 * ({@code common}, {@code llm}, {@code contracts}, {@code outbox}). Its {@code app.*} settings:
 * {@link AppPropertiesConfiguration}.
 */
@SpringBootApplication
@EnableScheduling   // only OutboxRelay is @Scheduled, and only with the kafka profile (task 2.2)
public class InterviewServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InterviewServiceApplication.class, args);
	}

}
