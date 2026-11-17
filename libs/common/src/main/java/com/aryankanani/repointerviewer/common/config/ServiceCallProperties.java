package com.aryankanani.repointerviewer.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.service-calls}: the retry and the circuit breaker of {@code ServiceCallGuard} (task 4.2), in every app
 * that calls another service over HTTP. The timeouts are the HTTP client's own
 * ({@code spring.http.serviceclient.<group>.connect-timeout} and {@code read-timeout}).
 *
 * <p>Validated at startup, and {@code ignoreUnknownFields = false} makes a misspelled key fail (task 4.1).
 *
 * @param maxAttempts tries of a call whose connection fails, the first one included
 * @param retryBackoff the wait before the second try, doubled before each next one
 * @param slidingWindowSize how many of the latest calls the breaker looks at
 * @param minimumCalls calls the breaker needs before it may open
 * @param failureRateThreshold percent of those calls that failed, from which the breaker opens
 * @param openDuration how long an open breaker fails every call at once, without sending it
 * @param trialCalls calls let through after that, whose results close the breaker or open it again
 */
@Validated
@ConfigurationProperties(prefix = "app.service-calls", ignoreUnknownFields = false)
public record ServiceCallProperties(@Min(1) int maxAttempts, @NotNull @DurationMin(millis = 1) Duration retryBackoff,
		@Min(1) int slidingWindowSize, @Min(1) int minimumCalls, @Min(1) @Max(100) int failureRateThreshold,
		@NotNull @DurationMin(millis = 1) Duration openDuration, @Min(1) int trialCalls) {
}
