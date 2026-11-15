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
 * doubles of {@link IntegrationTest}. GitHub, Ollama embeddings, the analysis pipeline and the Claude models are
 * all real, so an eval costs money and needs the network, Ollama on localhost:11434 and {@code ANTHROPIC_API_KEY}
 * in {@code .env}.
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
@TestPropertySource(properties = "spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}")
public @interface EvalTest {
}
