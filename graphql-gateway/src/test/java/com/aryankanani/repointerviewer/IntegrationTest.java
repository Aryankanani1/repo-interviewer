package com.aryankanani.repointerviewer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

/**
 * The gateway's full application context (no database since task 4.1 step 4a), plus a {@code GraphQlTester}
 * that runs queries through the real GraphQL engine (no HTTP), and test doubles for the two services behind it
 * ({@link TestDoublesConfiguration}): {@code FakeAnalysisApi} has the fixture repository's projects and code,
 * {@code FakeInterviewApi} the sessions, questions and answers a test adds (task 4.1).
 *
 * <p>Every test runs as {@link TestUsers#CANDIDATE} (task 4.4): the GraphQL engine is called directly, past the HTTP
 * filters, so there is no token to check; {@code @WithMockUser} on a test method changes the user, and
 * {@code AuthenticationTest} checks the tokens over HTTP.
 *
 * <p>Every integration test uses this one annotation, so Spring caches a single context. A test that adds its own
 * configuration (a mock bean, another profile) gets a second context, so avoid that.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDoublesConfiguration.class)
@AutoConfigureGraphQlTester
@WithMockUser(TestUsers.CANDIDATE)   // task 4.4: every test runs as the candidate unless it says otherwise
public @interface IntegrationTest {
}
