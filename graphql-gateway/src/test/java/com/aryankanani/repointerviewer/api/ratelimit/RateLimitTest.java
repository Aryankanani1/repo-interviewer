package com.aryankanani.repointerviewer.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.api.ratelimit.RateLimits.Operation;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

/**
 * Task 4.4 acceptance tests, 429: a user who submits projects or starts interviews faster than {@code app.rate-limits}
 * allows gets RATE_LIMITED, saying how long to wait, and the service behind is not called. Limits of 2 per hour here
 * (its own context; every other test runs with limits no test meets). Each test is another user, since the buckets
 * live as long as the context.
 */
@IntegrationTest
@TestPropertySource(properties = { "app.rate-limits.submit-project.capacity=2",
		"app.rate-limits.submit-project.refill-every=1h", "app.rate-limits.start-interview.capacity=2",
		"app.rate-limits.start-interview.refill-every=1h" })
class RateLimitTest {

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi analysis;

	private final FakeInterviewApi interviews;

	RateLimitTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
		this.interviews = interviews;
	}

	/** Acceptance criterion: the third submit within the hour is refused (429). */
	@Test
	@WithMockUser("submits-too-fast")
	void theThirdSubmitWithinTheHourIsRateLimited() {
		analysis.reset();
		submit().path("submitProject.errors").entityList(Object.class).hasSize(0);
		submit().path("submitProject.errors").entityList(Object.class).hasSize(0);

		submit()
				.path("submitProject.project").valueIsNull()
				.path("submitProject.errors[0].code").entity(String.class).isEqualTo("RATE_LIMITED")
				.path("submitProject.errors[0].message").entity(String.class)
				.isEqualTo("Too many requests. Try again in 60 minutes.");
		assertThat(analysis.calls("submit")).isEqualTo(2);
	}

	@Test
	@WithMockUser(TestUsers.CANDIDATE)
	void theThirdStartWithinTheHourIsRateLimited() {
		analysis.reset();
		interviews.reset();
		UUID project = analysis.readyProject();
		for (int i = 0; i < 2; i++) {
			interviews.onStart(new StartResponse(FakeInterviewApi.session(project), null, null));
			start(project).path("startInterview.errors").entityList(Object.class).hasSize(0);
		}

		start(project).path("startInterview.errors[0].code").entity(String.class).isEqualTo("RATE_LIMITED");
		assertThat(interviews.calls("start")).isEqualTo(2);
	}

	/** Each user and each operation has its own bucket. */
	@Test
	void bucketsArePerUserAndOperation() {
		RateLimitProperties.Limit onePerHour = new RateLimitProperties.Limit(1, Duration.ofHours(1));
		RateLimits limits = new RateLimits(new RateLimitProperties(onePerHour, onePerHour),
				InstantSource.fixed(Instant.parse("2026-11-18T10:00:00Z")));
		limits.take(Operation.SUBMIT_PROJECT, "alice");

		assertThatThrownBy(() -> limits.take(Operation.SUBMIT_PROJECT, "alice")).isInstanceOfSatisfying(
				DomainException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RATE_LIMITED));
		assertThatCode(() -> limits.take(Operation.SUBMIT_PROJECT, "bob")).doesNotThrowAnyException();
		assertThatCode(() -> limits.take(Operation.START_INTERVIEW, "alice")).doesNotThrowAnyException();
	}

	private GraphQlTester.Response submit() {
		return graphQlTester.document("""
				mutation ($input: SubmitProjectInput!) {
				  submitProject(input: $input) { project { id } errors { code message } }
				}
				""").variable("input", Map.of("githubUrl", "https://github.com/acme/shop")).execute();
	}

	private GraphQlTester.Response start(UUID project) {
		return graphQlTester.document("""
				mutation ($input: StartInterviewInput!) { startInterview(input: $input) { errors { code } } }
				""").variable("input", Map.of("projectId", project)).execute();
	}

}
