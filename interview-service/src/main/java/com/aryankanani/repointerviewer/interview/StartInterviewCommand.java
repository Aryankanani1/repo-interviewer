package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.contracts.interview.Difficulty;
import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import java.util.List;
import java.util.UUID;

/**
 * Input for {@link InterviewService#start}.
 *
 * @param difficulty null means JUNIOR
 * @param focusAreas null or empty means balanced
 * @param questionCount null means {@code app.interview.default-question-count}
 */
public record StartInterviewCommand(String userId, UUID projectId, Difficulty difficulty,
		List<QuestionCategory> focusAreas, Integer questionCount) {
}
