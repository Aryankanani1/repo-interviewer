package com.aryankanani.repointerviewer.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Task 4.4: the token bucket, with explicit instants instead of a clock (3 tokens, one more every 20 minutes). */
class TokenBucketTest {

	private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");

	private static final Duration TWENTY_MINUTES = Duration.ofMinutes(20);

	private final TokenBucket bucket = new TokenBucket(3, TWENTY_MINUTES, START);

	@Test
	void aFullBucketAllowsTheBurstThenRefusesAndSaysHowLongToWait() {
		assertThat(bucket.take(START)).isEmpty();
		assertThat(bucket.take(START)).isEmpty();
		assertThat(bucket.take(START)).isEmpty();

		assertThat(bucket.take(START)).contains(TWENTY_MINUTES);
		assertThat(bucket.take(START.plus(Duration.ofMinutes(5)))).contains(Duration.ofMinutes(15));
	}

	@Test
	void oneTokenComesBackPerRefillPeriod() {
		drain(START);

		assertThat(bucket.take(START.plus(TWENTY_MINUTES))).isEmpty();
		assertThat(bucket.take(START.plus(TWENTY_MINUTES))).isPresent();   // only one came back
	}

	@Test
	void aLongIdleTimeFillsItOnlyToCapacity() {
		drain(START);
		Instant dayLater = START.plus(Duration.ofDays(1));

		for (int i = 0; i < 3; i++) {
			assertThat(bucket.take(dayLater)).isEmpty();
		}
		assertThat(bucket.take(dayLater)).isPresent();
	}

	/** Earned time is kept as a fraction: two 10-minute waits make one token, none is lost to rounding. */
	@Test
	void partOfAPeriodIsNotLost() {
		drain(START);

		assertThat(bucket.take(START.plus(Duration.ofMinutes(10)))).contains(Duration.ofMinutes(10));
		assertThat(bucket.take(START.plus(TWENTY_MINUTES))).isEmpty();
	}

	private void drain(Instant at) {
		while (bucket.take(at).isEmpty()) {
			// take every token
		}
	}

}
