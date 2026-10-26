package com.aryankanani.repointerviewer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Task 0.1 acceptance test: the whole application context starts against a real
 * pgvector Postgres, which means Flyway applied V1 and JPA validation passed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RepoInterviewerApplicationTests {

	@Test
	void contextLoads() {
	}

}
