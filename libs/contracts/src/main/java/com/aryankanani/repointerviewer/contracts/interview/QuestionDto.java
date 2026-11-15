package com.aryankanani.repointerviewer.contracts.interview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A question that was asked (task 4.1). Questions written ahead and not asked yet never leave the interview side, and
 * neither does the rubric: the ideal answer outline comes only with a graded {@link AnswerDto}.
 *
 * @param order 1, 2, ... in the order they were asked, follow-ups included
 * @param parentQuestionId the question a follow-up follows; null for a main question
 */
public record QuestionDto(UUID id, UUID sessionId, int order, QuestionCategory category, String text,
		boolean followUp, List<CodeRef> codeRefs, Instant askedAt, UUID parentQuestionId) {
}
