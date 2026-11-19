package com.aryankanani.repointerviewer.contracts.interview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An interview session, as the interview side shows it to the rest of the system (task 4.1).
 *
 * @param userId the user who started it (task 4.4)
 * @param failureReason why the session FAILED; null otherwise
 * @param report null until the report is written
 * @param completedAt null while the session runs
 * @param currentQuestionId the question waiting for an answer; null when there is none
 */
public record SessionDto(UUID id, String userId, UUID projectId, SessionStatus status, Difficulty difficulty,
		List<QuestionCategory> focusAreas, int plannedQuestionCount, String failureReason, InterviewReport report,
		Instant startedAt, Instant completedAt, UUID currentQuestionId) {
}
