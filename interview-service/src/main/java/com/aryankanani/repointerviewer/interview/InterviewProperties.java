package com.aryankanani.repointerviewer.interview;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.interview}: question counts, follow-ups and answers (LLD §9). Question counts are capped at 8 because of
 * the {@code ck_session_count} check in interview-service's V1__init.sql. Validated at startup; a misspelled key fails.
 */
@Validated
@ConfigurationProperties(prefix = "app.interview", ignoreUnknownFields = false)
public record InterviewProperties(
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
