package com.aryankanani.repointerviewer.outbox;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.outbox}: {@code OutboxRelay} (task 2.2, kafka profile only): how often it polls, and how many rows one
 * poll locks.
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox", ignoreUnknownFields = false)
public record OutboxProperties(@NotNull @DurationMin(millis = 100) Duration pollInterval, @Positive int batchSize) {
}
