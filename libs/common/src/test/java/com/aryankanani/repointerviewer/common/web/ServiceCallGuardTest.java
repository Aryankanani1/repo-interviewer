package com.aryankanani.repointerviewer.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.config.ServiceCallProperties;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * Task 4.2: the retry and the circuit breaker, without a network. Each test's execution is the rest of the request
 * chain, which the guard calls once per try: it counts the tries and fails or answers as the test needs.
 */
class ServiceCallGuardTest {

	/** As in application.yml, with short waits: 3 tries, open at half of the last 10 calls (at least 5). */
	private static final ServiceCallProperties SETTINGS = new ServiceCallProperties(3, Duration.ofMillis(10), 10, 5, 50,
			Duration.ofMillis(200), 2);

	private static final String UNAVAILABLE = "The analysis service is not available right now. Try again in a minute.";

	private final ServiceCallGuard guard = new ServiceCallGuard("analysis service", SETTINGS);

	private final MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
			URI.create("http://analysis-service:8080/internal/projects/1/context"));

	private final AtomicInteger tries = new AtomicInteger();

	@Test
	void aRefusedConnectionIsTriedAgain() throws IOException {
		ClientHttpResponse ok = new MockClientHttpResponse(new byte[0], HttpStatus.OK);

		ClientHttpResponse response = guard.intercept(request, new byte[0], (request, body) -> {
			if (tries.incrementAndGet() < 3) {
				throw new ConnectException();
			}
			return ok;
		});

		assertThat(response).isSameAs(ok);
		assertThat(tries).hasValue(3);
	}

	@Test
	void aServiceThatStaysDownIsServiceUnavailableAfterThreeTries() {
		assertThatThrownBy(() -> guard.intercept(request, new byte[0], refused()))
				.isInstanceOfSatisfying(ServiceUnavailableException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
					assertThat(ex.getMessage()).isEqualTo(UNAVAILABLE);
					assertThat(ex).hasCauseInstanceOf(ConnectException.class);
				});
		assertThat(tries).hasValue(3);
	}

	/** The request reached the service, which may still be working on it: not sent again. */
	@Test
	void aReadTimeoutIsNotTriedAgain() {
		assertThatThrownBy(() -> guard.intercept(request, new byte[0], (request, body) -> {
			tries.incrementAndGet();
			throw new HttpTimeoutException("request timed out");
		})).isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE);
		assertThat(tries).hasValue(1);
	}

	/** 503 LLM_UNAVAILABLE comes from a service that is up: for the client's error handler, not the breaker. */
	@Test
	void anErrorAnswerIsAnAnswerNotAFailure() throws IOException {
		ClientHttpResponse unavailableModel = new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE);
		ClientHttpRequestExecution answers = (request, body) -> {
			tries.incrementAndGet();
			return unavailableModel;
		};

		for (int call = 0; call < 20; call++) {
			assertThat(guard.intercept(request, new byte[0], answers)).isSameAs(unavailableModel);
		}

		assertThat(tries).hasValue(20);
	}

	/** The breaker opens at the 5th refused try (5 calls at least, all failed); then a call fails without a try. */
	@Test
	void repeatedFailuresOpenTheBreakerAndThenCallsFailAtOnce() {
		callTwiceWhileDown();
		int before = tries.get();

		Instant start = Instant.now();
		assertThatThrownBy(() -> guard.intercept(request, new byte[0], refused()))
				.isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE)
				.hasCauseInstanceOf(CallNotPermittedException.class);

		assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(50));
		assertThat(tries).hasValue(before);   // no request sent
	}

	/** After the open duration, 2 trial calls go through; both answer, so the breaker closes and calls flow again. */
	@Test
	void trialCallsThatSucceedCloseTheBreaker() throws Exception {
		callTwiceWhileDown();
		Thread.sleep(SETTINGS.openDuration().plusMillis(50).toMillis());
		ClientHttpResponse ok = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
		ClientHttpRequestExecution answers = (request, body) -> {
			tries.incrementAndGet();
			return ok;
		};
		int before = tries.get();

		for (int call = 0; call < 3; call++) {   // the third would be refused if the breaker had not closed
			assertThat(guard.intercept(request, new byte[0], answers)).isSameAs(ok);
		}

		assertThat(tries.get() - before).isEqualTo(3);
	}

	/** 3 refused tries, then 2: the 5th opens the breaker, which refuses the second call's last try unsent. */
	private void callTwiceWhileDown() {
		for (int call = 0; call < 2; call++) {
			assertThatThrownBy(() -> guard.intercept(request, new byte[0], refused()))
					.isInstanceOf(ServiceUnavailableException.class);
		}
		assertThat(tries).hasValue(5);
	}

	private ClientHttpRequestExecution refused() {
		return (request, body) -> {
			tries.incrementAndGet();
			throw new ConnectException("Connection refused");
		};
	}

}
