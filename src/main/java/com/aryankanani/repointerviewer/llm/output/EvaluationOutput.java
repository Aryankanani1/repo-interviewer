package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/**
 * EVALUATION: the grade of one answer ({@code answer-evaluation.st}). The score is clamped to 0..10 here; the
 * verdict and whether a follow-up is actually asked are decided by code ({@code Scoring}, {@code FollowUpPolicy},
 * DECISIONS.md D-007), not by {@code followUpNeeded} alone.
 */
public record EvaluationOutput(int score, List<String> coveredPoints, List<String> missedPoints,
		List<String> incorrectClaims, String feedback, boolean followUpNeeded, String followUpReason)
		implements LlmOutput<EvaluationOutput> {

	static final int MIN_SCORE = 0;

	static final int MAX_SCORE = 10;

	static final int MAX_POINTS = 10;

	@Override
	public EvaluationOutput normalized() {
		return new EvaluationOutput(Normalize.clamp(score, MIN_SCORE, MAX_SCORE),
				Normalize.texts(coveredPoints, MAX_POINTS), Normalize.texts(missedPoints, MAX_POINTS),
				Normalize.texts(incorrectClaims, MAX_POINTS), Normalize.text(feedback), followUpNeeded,
				Normalize.text(followUpReason));
	}

}
