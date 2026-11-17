package com.aryankanani.repointerviewer.common.web;

import com.aryankanani.repointerviewer.common.config.ServiceCallProperties;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Guards a {@code RestClient}'s calls to another service of this system (task 4.2, DECISIONS.md D-027): a retry
 * around a circuit breaker, as a request interceptor, so it covers every method of the client's interface.
 * <ul>
 * <li><b>A failure is no answer</b>: the connection failed or the read timed out (an {@link IOException}). Any HTTP
 * answer, even 404 NOT_FOUND or 503 LLM_UNAVAILABLE, shows the service is up: it goes to the client's error handler
 * unchanged and counts as a success.</li>
 * <li><b>Retry</b> only a connection that failed (refused, or not open within the connect timeout): the request
 * never reached the other service, so even a call that costs model calls cannot run twice. Not a read timeout: the
 * call may still be running there, and waiting all over again is the opposite of failing fast.</li>
 * <li><b>Circuit breaker</b>: when too many of the latest calls failed, it opens, and every call fails at once,
 * without a request, for a while; then a few trial calls close it again or open it for another while. A service that
 * is down is asked again only now and then, and its callers get their answer in microseconds.</li>
 * <li>A call that still failed, or that the open breaker refused, throws {@link ServiceUnavailableException}, which
 * names the service and says to try again later.</li>
 * </ul>
 * The timeouts are the HTTP client's own: they stop the request itself, where Resilience4j's TimeLimiter would only
 * stop waiting for it (on another thread, which stays blocked). They decide which call is too slow, so the breaker
 * counts no "slow but answered" calls. Settings: {@code app.service-calls} ({@link ServiceCallProperties}).
 */
public final class ServiceCallGuard implements ClientHttpRequestInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ServiceCallGuard.class);

	/** Longer than any read timeout: an answer, however slow, is never a failure (Resilience4j's default is 60 s). */
	private static final Duration NEVER_SLOW = Duration.ofDays(1);

	private final String service;

	private final CircuitBreaker breaker;

	private final Retry retry;

	/** @param service the other service as a user may read it, for example "analysis service" */
	public ServiceCallGuard(String service, ServiceCallProperties settings) {
		this.service = service;
		this.breaker = CircuitBreaker.of(service, CircuitBreakerConfig.custom()
				.slidingWindowSize(settings.slidingWindowSize())   // the latest N calls (count-based)
				.minimumNumberOfCalls(settings.minimumCalls())
				.failureRateThreshold(settings.failureRateThreshold())
				.waitDurationInOpenState(settings.openDuration())
				.permittedNumberOfCallsInHalfOpenState(settings.trialCalls())
				.slowCallDurationThreshold(NEVER_SLOW)
				.recordExceptions(IOException.class)
				.build());
		this.retry = Retry.of(service, RetryConfig.custom()
				.maxAttempts(settings.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialBackoff(settings.retryBackoff(), 2))
				.retryOnException(ServiceCallGuard::connectionFailed)
				.build());
		breaker.getEventPublisher().onStateTransition(
				event -> log.warn("Calls to the {}: circuit breaker {}", service, event.getStateTransition()));
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) {
		try {
			// Each try goes through the breaker, so it counts each one, and an open breaker is not retried.
			return retry.executeCheckedSupplier(
					() -> breaker.executeCheckedSupplier(() -> execution.execute(request, body)));
		}
		catch (CallNotPermittedException ex) {
			throw new ServiceUnavailableException(message(), ex);
		}
		catch (IOException ex) {
			log.warn("{} {} to the {} failed: {}", request.getMethod(), request.getURI().getPath(), service,
					ex.toString());
			throw new ServiceUnavailableException(message(), ex);
		}
		catch (RuntimeException | Error ex) {
			throw ex;
		}
		catch (Throwable ex) {   // execute declares only IOException
			throw new IllegalStateException(ex);
		}
	}

	private String message() {
		return "The " + service + " is not available right now. Try again in a minute.";
	}

	/**
	 * The request was never sent. These are the JDK HttpClient's exceptions, the client each app sets with
	 * {@code spring.http.clients.imperative.factory: jdk}; another client throws others for the same failures.
	 */
	private static boolean connectionFailed(Throwable ex) {
		return ex instanceof ConnectException || ex instanceof HttpConnectTimeoutException;
	}

}
