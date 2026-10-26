package com.aryankanani.repointerviewer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context on a real pgvector Postgres (Testcontainers), plus a {@code GraphQlTester}
 * that runs queries through the real GraphQL engine (no HTTP), and test doubles for GitHub and the
 * analysis pipeline ({@link TestDoublesConfiguration}).
 *
 * <p>Every integration test uses this one annotation, so Spring caches a single context and the
 * whole suite shares one container. A test that adds its own configuration (a mock bean, another
 * profile) gets a second context and a second container, so avoid that.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestDoublesConfiguration.class })
@AutoConfigureGraphQlTester
public @interface IntegrationTest {
}
