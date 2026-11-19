package com.aryankanani.repointerviewer.api.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.rate-limits} (task 4.4): one token bucket per user for each expensive mutation ({@link RateLimits}).
 * Validated at startup; a misspelled key fails it (task 4.1).
 *
 * @param submitProject {@code submitProject}: each one clones a repository and makes one summary call
 * @param startInterview {@code startInterview}: each one makes a plan call and at least one question call
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limits", ignoreUnknownFields = false)
public record RateLimitProperties(@NotNull @Valid Limit submitProject, @NotNull @Valid Limit startInterview) {

	/**
	 * @param capacity requests allowed at once (the burst)
	 * @param refillEvery the time to earn one more request back
	 */
	public record Limit(@Min(1) int capacity, @NotNull @DurationMin(millis = 1) Duration refillEvery) {
	}

}
