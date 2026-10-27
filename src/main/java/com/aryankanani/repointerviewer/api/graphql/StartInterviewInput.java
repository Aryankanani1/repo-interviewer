package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.interview.Difficulty;
import java.util.List;
import java.util.UUID;

/** GraphQL input {@code StartInterviewInput}. Schema defaults (JUNIOR, 5) apply when a field is left out. */
record StartInterviewInput(UUID projectId, Difficulty difficulty, List<QuestionCategory> focusAreas,
		Integer questionCount) {
}
