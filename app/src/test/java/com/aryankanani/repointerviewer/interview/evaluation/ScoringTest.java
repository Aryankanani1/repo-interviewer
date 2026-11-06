package com.aryankanani.repointerviewer.interview.evaluation;

import static com.aryankanani.repointerviewer.common.QuestionCategory.ARCHITECTURE;
import static com.aryankanani.repointerviewer.common.QuestionCategory.TESTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.interview.AnswerVerdict;
import com.aryankanani.repointerviewer.interview.CategoryScore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tasks 1.11 and 1.12: the grade arithmetic of LLD §9.5, done in code. */
class ScoringTest {

	@ParameterizedTest(name = "score {0} -> {1}")
	@CsvSource({ "0, OFF_TOPIC", "1, WEAK", "4, WEAK", "5, ADEQUATE", "7, ADEQUATE", "8, STRONG", "10, STRONG" })
	void scoresMapToVerdictBands(int score, AnswerVerdict verdict) {
		assertThat(Scoring.verdict(score)).isEqualTo(verdict);
	}

	/** Acceptance criterion: a follow-up weighs half. (8·1 + 4·0.5) / 1.5 = 6.67 → 6.7. */
	@Test
	void theOverallScoreWeighsAFollowUpHalf() {
		assertThat(Scoring.overall(List.of(main(ARCHITECTURE, 8), followUp(ARCHITECTURE, 4)))).isEqualByComparingTo("6.7");
	}

	@Test
	void theOverallScoreIsRoundedHalfUpToOneDecimal() {
		// 25 / 4 = 6.25 → 6.3
		assertThat(Scoring.overall(List.of(main(TESTING, 6), main(TESTING, 6), main(TESTING, 6), main(TESTING, 7))))
				.isEqualByComparingTo("6.3");
		assertThat(Scoring.overall(List.of(main(TESTING, 9)))).isEqualByComparingTo("9.0");
	}

	@Test
	void onlyFollowUpsGiveTheirPlainAverage() {
		assertThat(Scoring.overall(List.of(followUp(TESTING, 4), followUp(TESTING, 6)))).isEqualByComparingTo("5.0");
	}

	/** Acceptance criterion: category averages are plain (unweighted), with their count, in a stable order. */
	@Test
	void categoryAveragesArePlainWithTheirCountInEnumOrder() {
		List<CategoryScore> scores = Scoring.categoryScores(List.of(
				main(TESTING, 4), main(ARCHITECTURE, 9), followUp(ARCHITECTURE, 6)));

		assertThat(scores).containsExactly(
				new CategoryScore(ARCHITECTURE, 7.5, 2),   // (9 + 6) / 2: the follow-up is not weighted here
				new CategoryScore(TESTING, 4.0, 1));
	}

	@Test
	void withNoGradedAnswerThereIsNothingToScore() {
		assertThatThrownBy(() -> Scoring.overall(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThat(Scoring.categoryScores(List.of())).isEmpty();
	}

	private static GradedAnswer main(QuestionCategory category, int score) {
		return new GradedAnswer(category, false, score);
	}

	private static GradedAnswer followUp(QuestionCategory category, int score) {
		return new GradedAnswer(category, true, score);
	}

}
