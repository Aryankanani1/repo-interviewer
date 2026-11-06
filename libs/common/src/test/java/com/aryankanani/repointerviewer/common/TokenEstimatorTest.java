package com.aryankanani.repointerviewer.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TokenEstimatorTest {

	@ParameterizedTest(name = "\"{0}\" is {1} tokens")
	@CsvSource({ "'', 0", "a, 1", "abcd, 1", "abcde, 2", "abcdefgh, 2", "abcdefghi, 3" })
	void roundsUpToWholeTokensOfFourCharacters(String text, int tokens) {
		assertThat(TokenEstimator.estimate(text)).isEqualTo(tokens);
	}

}
