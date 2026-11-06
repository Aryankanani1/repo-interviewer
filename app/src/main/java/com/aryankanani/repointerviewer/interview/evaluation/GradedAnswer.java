package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.common.QuestionCategory;

/** One graded answer, as the score arithmetic needs it. */
public record GradedAnswer(QuestionCategory category, boolean followUp, int score) {
}
