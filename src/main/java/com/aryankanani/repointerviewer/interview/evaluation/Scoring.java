package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.interview.AnswerVerdict;

/**
 * Grade arithmetic, in code (LLD §9.5, DECISIONS.md D-007): models are inconsistent at thresholds, so the model
 * gives a score and code turns it into a verdict. The weighted overall score arrives with the report (task 1.12).
 */
public final class Scoring {

	private Scoring() {
	}

	/** 8..10 STRONG, 5..7 ADEQUATE, 1..4 WEAK, 0 OFF_TOPIC. The score is already clamped to 0..10. */
	public static AnswerVerdict verdict(int score) {
		if (score >= 8) {
			return AnswerVerdict.STRONG;
		}
		if (score >= 5) {
			return AnswerVerdict.ADEQUATE;
		}
		return score >= 1 ? AnswerVerdict.WEAK : AnswerVerdict.OFF_TOPIC;
	}

}
