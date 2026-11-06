package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.interview.AnswerVerdict;
import com.aryankanani.repointerviewer.interview.CategoryScore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Grade arithmetic, in code (LLD §9.5, DECISIONS.md D-007): models are inconsistent at arithmetic and thresholds,
 * so the model gives one score per answer and code does everything else. Pure functions, exact decimals.
 */
public final class Scoring {

	private static final BigDecimal MAIN_WEIGHT = BigDecimal.ONE;

	private static final BigDecimal FOLLOW_UP_WEIGHT = new BigDecimal("0.5");

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

	/** A follow-up probes a gap in the same topic, so it counts half: it should not swing the result alone. */
	public static BigDecimal weight(boolean followUp) {
		return followUp ? FOLLOW_UP_WEIGHT : MAIN_WEIGHT;
	}

	/**
	 * {@code Σ weight·score / Σ weight}, rounded half up to one decimal.
	 *
	 * @throws IllegalArgumentException with no graded answers: there is nothing to score
	 */
	public static BigDecimal overall(List<GradedAnswer> answers) {
		if (answers.isEmpty()) {
			throw new IllegalArgumentException("No graded answers to score");
		}
		BigDecimal weighted = BigDecimal.ZERO;
		BigDecimal weights = BigDecimal.ZERO;
		for (GradedAnswer answer : answers) {
			BigDecimal weight = weight(answer.followUp());
			weighted = weighted.add(weight.multiply(BigDecimal.valueOf(answer.score())));
			weights = weights.add(weight);
		}
		return weighted.divide(weights, 1, RoundingMode.HALF_UP);
	}

	/** Per category, in enum order: the plain (unweighted) average, one decimal, and how many answers it covers. */
	public static List<CategoryScore> categoryScores(List<GradedAnswer> answers) {
		Map<QuestionCategory, List<Integer>> scores = new EnumMap<>(QuestionCategory.class);
		answers.forEach(answer -> scores.computeIfAbsent(answer.category(), category -> new ArrayList<>())
				.add(answer.score()));
		List<CategoryScore> result = new ArrayList<>();
		scores.forEach((category, list) -> {
			BigDecimal sum = BigDecimal.valueOf(list.stream().mapToInt(Integer::intValue).sum());
			result.add(new CategoryScore(category,
					sum.divide(BigDecimal.valueOf(list.size()), 1, RoundingMode.HALF_UP).doubleValue(), list.size()));
		});
		return result;
	}

}
