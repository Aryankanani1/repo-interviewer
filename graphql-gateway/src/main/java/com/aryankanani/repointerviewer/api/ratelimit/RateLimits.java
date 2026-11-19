package com.aryankanani.repointerviewer.api.ratelimit;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rate limits on the mutations that cost money (task 4.4, DECISIONS.md D-031): a {@link TokenBucket} per user and
 * operation, sized by {@code app.rate-limits}. Checked after the input is valid and the caller may act, right before
 * the service is called, so a typo or a refused request costs no token.
 *
 * <p>In this gateway's memory: with more than one gateway instance each would have its own buckets, and a restart
 * empties them all (full buckets again). One bucket per user and operation stays for the life of the process.
 */
@Component
public class RateLimits {

	public enum Operation {
		SUBMIT_PROJECT, START_INTERVIEW
	}

	private final RateLimitProperties settings;

	private final InstantSource clock;

	private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

	@Autowired
	RateLimits(RateLimitProperties settings) {
		this(settings, InstantSource.system());
	}

	RateLimits(RateLimitProperties settings, InstantSource clock) {
		this.settings = settings;
		this.clock = clock;
	}

	/** @throws DomainException RATE_LIMITED, saying how long to wait, when the user's bucket is empty */
	public void take(Operation operation, String userId) {
		RateLimitProperties.Limit limit = switch (operation) {
			case SUBMIT_PROJECT -> settings.submitProject();
			case START_INTERVIEW -> settings.startInterview();
		};
		buckets.computeIfAbsent(operation + " " + userId,
				key -> new TokenBucket(limit.capacity(), limit.refillEvery(), clock.instant()))
				.take(clock.instant())
				.ifPresent(wait -> {
					throw new DomainException(ErrorCode.RATE_LIMITED, "Too many requests. Try again in " + inWords(wait)
							+ ".");
				});
	}

	/** Rounded up: "1 minute", "3 minutes", "40 seconds". */
	private static String inWords(Duration wait) {
		long seconds = (wait.toMillis() + 999) / 1000;
		if (seconds < 60) {
			return seconds + (seconds == 1 ? " second" : " seconds");
		}
		long minutes = (seconds + 59) / 60;
		return minutes + (minutes == 1 ? " minute" : " minutes");
	}

}
