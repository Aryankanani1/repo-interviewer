package com.aryankanani.repointerviewer.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.consumer-retry}: Kafka consumers (task 2.4, kafka profile only), in every app that has a listener. How
 * often a retryable event is delivered again before it goes to the dead-letter topic, and the first wait, which
 * doubles each time.
 *
 * <p>Each app turns on the {@code app.*} records it uses (task 4.1; there was one {@code AppProperties} for all
 * until then). Validated at startup, and {@code ignoreUnknownFields = false} makes a misspelled key fail.
 */
@Validated
@ConfigurationProperties(prefix = "app.consumer-retry", ignoreUnknownFields = false)
public record ConsumerRetryProperties(@Min(0) int maxRetries,
		@NotNull @DurationMin(millis = 10) Duration initialBackoff) {
}
