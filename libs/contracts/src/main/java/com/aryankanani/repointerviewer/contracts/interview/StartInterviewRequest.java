package com.aryankanani.repointerviewer.contracts.interview;

import java.util.List;
import java.util.UUID;

/**
 * Input of {@link InterviewApi#start}.
 *
 * @param userId the JWT subject of the caller (task 4.4); the session is theirs
 * @param difficulty null means JUNIOR
 * @param focusAreas null or empty means balanced
 * @param questionCount null means the interview side's default
 */
public record StartInterviewRequest(String userId, UUID projectId, Difficulty difficulty,
		List<QuestionCategory> focusAreas, Integer questionCount) {
}
