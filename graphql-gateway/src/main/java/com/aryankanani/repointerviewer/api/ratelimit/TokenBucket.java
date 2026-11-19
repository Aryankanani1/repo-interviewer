package com.aryankanani.repointerviewer.api.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A token bucket (task 4.4): it holds at most {@code capacity} tokens and gains one every {@code refillEvery}; each
 * request takes one, and an empty bucket refuses. So a user can make {@code capacity} requests at once, then one per
 * {@code refillEvery} on average: bursts are allowed, a steady flood is not.
 *
 * <p>Refilled lazily: no timer, the tokens earned since the last request are added when the next one comes, as a
 * fraction, so a refill period never gets lost in rounding. Thread-safe; one per user and operation.
 */
final class TokenBucket {

	private final int capacity;

	private final Duration refillEvery;

	private double tokens;

	private Instant updatedAt;

	/** Full at the start: a new user can use the whole burst. */
	TokenBucket(int capacity, Duration refillEvery, Instant now) {
		this.capacity = capacity;
		this.refillEvery = refillEvery;
		this.tokens = capacity;
		this.updatedAt = now;
	}

	/** @return empty when a token was taken; otherwise how long until the next one, and nothing was taken */
	synchronized Optional<Duration> take(Instant now) {
		if (now.isAfter(updatedAt)) {
			double earned = (double) Duration.between(updatedAt, now).toNanos() / refillEvery.toNanos();
			tokens = Math.min(capacity, tokens + earned);
			updatedAt = now;
		}
		if (tokens >= 1) {
			tokens -= 1;
			return Optional.empty();
		}
		return Optional.of(Duration.ofNanos((long) Math.ceil((1 - tokens) * refillEvery.toNanos())));
	}

}
