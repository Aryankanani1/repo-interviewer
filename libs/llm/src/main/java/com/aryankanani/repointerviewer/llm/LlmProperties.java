package com.aryankanani.repointerviewer.llm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.llm}: the model per purpose, output caps and call budgets of {@link LlmGateway} (LLD §5.4), in each
 * service that calls a model. Validated at startup; {@code ignoreUnknownFields = false} makes a misspelled key fail.
 * Every nested section needs {@code @Valid}; without it the constraints inside that section are never checked.
 */
@Validated
@ConfigurationProperties(prefix = "app.llm", ignoreUnknownFields = false)
public record LlmProperties(
		@Valid @NotNull Models models,
		@Valid @NotNull MaxOutputTokens maxOutputTokens,
		@Positive int maxCallsPerSession,
		@Positive int maxCallsPerDay,
		@NotNull @DurationMin(seconds = 1) Duration timeout) {

	@AssertTrue(message = "must satisfy max-calls-per-session <= max-calls-per-day")
	boolean isCallLimitsValid() {
		return maxCallsPerSession <= maxCallsPerDay;
	}

	/** Model id per LLM purpose. Fixed fields, one per {@code LlmPurpose}, so a missing one fails at startup. */
	public record Models(
			@NotBlank String summary,
			@NotBlank String plan,
			@NotBlank String question,
			@NotBlank String followUp,
			@NotBlank String evaluation,
			@NotBlank String report) {
	}

	public record MaxOutputTokens(
			@Positive int summary,
			@Positive int plan,
			@Positive int question,
			@Positive int followUp,
			@Positive int evaluation,
			@Positive int report) {
	}

}
