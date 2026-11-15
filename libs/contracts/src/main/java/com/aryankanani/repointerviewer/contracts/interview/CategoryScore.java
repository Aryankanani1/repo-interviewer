package com.aryankanani.repointerviewer.contracts.interview;


/**
 * The plain average score of the graded answers in one category. GraphQL type {@code CategoryScore}.
 *
 * @param averageScore one decimal
 */
public record CategoryScore(QuestionCategory category, double averageScore, int questionCount) {
}
