package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.interview.InterviewReport;
import com.aryankanani.repointerviewer.interview.InterviewSession;
import com.aryankanani.repointerviewer.interview.SessionStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * GraphQL type {@code InterviewSession}. {@code project}, {@code questions} and {@code currentQuestion} have their
 * own resolvers in {@link InterviewController}, which use {@code projectId} and {@code currentQuestionId} (not
 * schema fields, so clients never see them). {@code report} is the stored {@link InterviewReport}, whose field
 * names match the GraphQL types.
 */
record InterviewSessionView(
		UUID id,
		SessionStatus status,
		Difficulty difficulty,
		List<QuestionCategory> focusAreas,
		int plannedQuestionCount,
		String failureReason,
		InterviewReport report,
		OffsetDateTime startedAt,
		OffsetDateTime completedAt,
		UUID projectId,
		UUID currentQuestionId) {

	static InterviewSessionView from(InterviewSession session) {
		return new InterviewSessionView(session.getId(), session.getStatus(), session.getDifficulty(),
				session.getFocusAreas(), session.getPlannedQuestionCount(), session.getFailureReason(),
				session.getReport(), session.getStartedAt().atOffset(ZoneOffset.UTC),
				session.getCompletedAt() == null ? null : session.getCompletedAt().atOffset(ZoneOffset.UTC),
				session.getProjectId(), session.getCurrentQuestionId());
	}

}
