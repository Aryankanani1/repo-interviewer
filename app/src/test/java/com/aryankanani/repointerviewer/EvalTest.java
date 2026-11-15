package com.aryankanani.repointerviewer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * An eval (task 1.17, LLD §12): the real application on a Testcontainers Postgres, with <b>none</b> of the test
 * doubles of {@link IntegrationTest}. The Claude models are real, and so is analysis-service (task 4.1), reached over
 * HTTP at {@code ANALYSIS_BASE_URL} (default http://localhost:8082, its dev port): start it first, in dev
 * ({@code ./mvnw -pl analysis-service -am spring-boot:run}), with Compose Postgres and Ollama on localhost:11434.
 * An eval costs money and needs the network and {@code ANTHROPIC_API_KEY} in {@code .env}.
 *
 * <p>Tagged {@code eval}: skipped by {@code ./mvnw verify}, run alone with {@code ./mvnw verify -Pevals}. Ask Aryan
 * before running it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("eval")
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}",
		"spring.http.serviceclient.analysis.base-url=${ANALYSIS_BASE_URL:http://localhost:${ANALYSIS_PORT:8082}}" })
public @interface EvalTest {
}
