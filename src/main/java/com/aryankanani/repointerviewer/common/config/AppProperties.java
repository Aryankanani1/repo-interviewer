package com.aryankanani.repointerviewer.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed view of every {@code app.*} key in application.yml (docs/LLD.md §10).
 *
 * <p>Validated at startup: a bad value stops the application with a message that names the key.
 * {@code ignoreUnknownFields = false} makes a misspelled key fail too, instead of being silently ignored.
 * Every nested section needs {@code @Valid}; without it the constraints inside that section are never checked.
 */
@Validated
@ConfigurationProperties(prefix = "app", ignoreUnknownFields = false)
public record AppProperties(
		@Valid @NotNull Repo repo,
		@Valid @NotNull Chunking chunking,
		@Valid @NotNull Embedding embedding,
		@Valid @NotNull Retrieval retrieval,
		@Valid @NotNull RepoMap repoMap,
		@Valid @NotNull Analysis analysis,
		@Valid @NotNull Interview interview,
		@Valid @NotNull Llm llm) {

	public record Repo(
			@NotBlank String allowedHost,
			@Positive int maxFiles,
			@Positive long maxFileBytes,
			@Positive long maxRepoBytes,
			@NotNull @DurationMin(seconds = 1) Duration lsRemoteTimeout,
			@NotNull @DurationMin(seconds = 1) Duration cloneTimeout,
			@NotNull Path workDir) {
	}

	public record Chunking(@Positive int maxChunkLines, @Positive int smallMethodLines) {
	}

	/** {@code dimensions} must match the {@code vector(768)} column in V1__init.sql. */
	public record Embedding(@Positive int batchSize, @Positive int dimensions) {
	}

	public record Retrieval(@Positive int topK, @Positive int maxContextTokens) {
	}

	public record RepoMap(@Positive int maxTokens) {
	}

	public record Analysis(@NotNull @DurationMin(minutes = 1) Duration staleAfter) {
	}

	/** Question counts are capped at 8 because of the {@code ck_session_count} check in V1__init.sql. */
	public record Interview(
			@Min(1) @Max(8) int defaultQuestionCount,
			@Min(1) @Max(8) int minQuestionCount,
			@Min(1) @Max(8) int maxQuestionCount,
			@Min(0) int maxFollowUpsPerTopic,
			@Min(0) @Max(10) int followUpScoreMin,
			@Min(0) @Max(10) int followUpScoreMax,
			@Positive int answerMaxChars,
			boolean prefetchEnabled) {

		@AssertTrue(message = "must satisfy min-question-count <= default-question-count <= max-question-count")
		boolean isQuestionCountRangeValid() {
			return minQuestionCount <= defaultQuestionCount && defaultQuestionCount <= maxQuestionCount;
		}

		@AssertTrue(message = "must satisfy follow-up-score-min <= follow-up-score-max")
		boolean isFollowUpScoreRangeValid() {
			return followUpScoreMin <= followUpScoreMax;
		}

	}

	public record Llm(
			@Valid @NotNull Models models,
			@Valid @NotNull MaxOutputTokens maxOutputTokens,
			@Positive int maxCallsPerSession,
			@Positive int maxCallsPerDay,
			@NotNull @DurationMin(seconds = 1) Duration timeout) {

		@AssertTrue(message = "must satisfy max-calls-per-session <= max-calls-per-day")
		boolean isCallLimitsValid() {
			return maxCallsPerSession <= maxCallsPerDay;
		}

	}

	/** Model id per LLM purpose. Fixed fields, not a map keyed by {@code LlmPurpose}: common may not import llm. */
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
