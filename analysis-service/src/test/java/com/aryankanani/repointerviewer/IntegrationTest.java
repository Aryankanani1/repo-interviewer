package com.aryankanani.repointerviewer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * analysis-service's full context on a real pgvector Postgres (Testcontainers, as {@code analysis_svc}), with test
 * doubles for GitHub, the analysis pipeline, the embedding model and Claude ({@link TestDoublesConfiguration}), and
 * {@link FixtureIndexer} to load the fixture repository into the database.
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
@Import({ TestcontainersConfiguration.class, TestDoublesConfiguration.class, FixtureIndexer.class })
public @interface IntegrationTest {
}
