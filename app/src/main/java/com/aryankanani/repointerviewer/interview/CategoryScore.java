package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;

/**
 * The plain average score of the graded answers in one category. GraphQL type {@code CategoryScore}.
 *
 * @param averageScore one decimal
 */
public record CategoryScore(QuestionCategory category, double averageScore, int questionCount) {
}
