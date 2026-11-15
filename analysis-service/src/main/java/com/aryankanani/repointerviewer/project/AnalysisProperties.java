package com.aryankanani.repointerviewer.project;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code app.analysis}: after how long {@link StaleAnalysisRecovery} gives up on an analysis that stopped moving. */
@Validated
@ConfigurationProperties(prefix = "app.analysis", ignoreUnknownFields = false)
public record AnalysisProperties(@NotNull @DurationMin(minutes = 1) Duration staleAfter) {
}
