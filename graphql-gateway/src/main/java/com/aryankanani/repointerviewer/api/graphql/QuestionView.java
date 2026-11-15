package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * GraphQL type {@code Question}. Deliberately without the rubric and the ideal outline: they would give the answer
 * away (they appear only on {@code AnswerEvaluation}, after answering). {@code answer} and {@code parent} have
 * batch resolvers in {@link InterviewController}, which use {@code parentQuestionId} (not a schema field).
 */
record QuestionView(
		UUID id,
		int order,
		QuestionCategory category,
		String text,
		boolean isFollowUp,
		List<CodeRefView> codeRefs,
		OffsetDateTime askedAt,
		UUID parentQuestionId) {

	static QuestionView from(QuestionDto question) {
		return new QuestionView(question.id(), question.order(), question.category(), question.text(),
				question.followUp(),
				question.codeRefs().stream().map(ref -> CodeRefView.from(ref, question.sessionId())).toList(),
				question.askedAt().atOffset(ZoneOffset.UTC),
				question.parentQuestionId());
	}

}
