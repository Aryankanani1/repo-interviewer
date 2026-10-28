package com.aryankanani.repointerviewer.interview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.interview.AnswerVerdict;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Task 1.11: the verdict bands of LLD §9.5, at every boundary. */
class ScoringTest {

	@ParameterizedTest(name = "score {0} -> {1}")
	@CsvSource({ "0, OFF_TOPIC", "1, WEAK", "4, WEAK", "5, ADEQUATE", "7, ADEQUATE", "8, STRONG", "10, STRONG" })
	void scoresMapToVerdictBands(int score, AnswerVerdict verdict) {
		assertThat(Scoring.verdict(score)).isEqualTo(verdict);
	}

}
