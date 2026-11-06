package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.interview.Question;
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

	static QuestionView from(Question question) {
		return new QuestionView(question.getId(), question.getOrderIndex(), question.getCategory(), question.getText(),
				question.isFollowUp(),
				question.getCodeRefs().stream().map(ref -> CodeRefView.from(ref, question.getSessionId())).toList(),
				question.getAskedAt().atOffset(ZoneOffset.UTC),
				question.getParentQuestionId());
	}

}
