package com.aryankanani.repointerviewer;

import org.junit.jupiter.api.Test;

/**
 * The whole context of interview-service starts against a real Postgres as {@code interview_svc}, which means
 * Flyway applied its V1 in the schema "interview" and JPA validation passed (task 4.1).
 */
@IntegrationTest
class InterviewServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
