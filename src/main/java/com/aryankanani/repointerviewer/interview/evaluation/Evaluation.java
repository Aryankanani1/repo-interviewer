package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.interview.AnswerVerdict;
import java.util.List;

/**
 * The grade of one answer: the model's score and points, and the verdict code derived from the score.
 *
 * @param followUpNeeded the model's opinion only; {@link FollowUpPolicy} decides whether a follow-up is asked
 */
public record Evaluation(int score, AnswerVerdict verdict, String feedback, List<String> coveredPoints,
		List<String> missedPoints, List<String> incorrectClaims, boolean followUpNeeded, String followUpReason) {
}
