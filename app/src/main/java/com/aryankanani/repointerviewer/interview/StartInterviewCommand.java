package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import java.util.List;
import java.util.UUID;

/**
 * Input for {@link InterviewService#start}.
 *
 * @param difficulty null means JUNIOR
 * @param focusAreas null or empty means balanced
 * @param questionCount null means {@code app.interview.default-question-count}
 */
public record StartInterviewCommand(UUID projectId, Difficulty difficulty, List<QuestionCategory> focusAreas,
		Integer questionCount) {
}
