package com.aryankanani.repointerviewer.interview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Task 1.11 acceptance criteria for {@link FollowUpPolicy}: code decides, not the model (D-007). */
class FollowUpPolicyTest {

	/** The defaults: scores 3..7, one follow-up per topic, 30 calls per session. */
	private final FollowUpPolicy policy = new FollowUpPolicy(3, 7, 1, 30);

	/** Acceptance criterion: a score outside 3..7 never triggers a follow-up, whatever the model says. */
	@ParameterizedTest(name = "score {0}")
	@ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 })
	void onlyScoresFromThreeToSevenCanTriggerAFollowUp(int score) {
		assertThat(policy.shouldFollowUp(evaluation(score, true), 0, 0, 2)).isEqualTo(score >= 3 && score <= 7);
	}

	@Test
	void theModelMustAskForOne() {
		assertThat(policy.shouldFollowUp(evaluation(5, false), 0, 0, 2)).isFalse();
	}

	/** Acceptance criterion: at most one follow-up per topic. */
	@Test
	void aTopicGetsAtMostOneFollowUp() {
		assertThat(policy.shouldFollowUp(evaluation(5, true), 1, 0, 2)).isFalse();
	}

	/** 2 topics left need 3 + 2 * 3 + 1 = 10 calls: exactly 10 left is enough, 9 is not. */
	@Test
	void aFollowUpOnlyUsesBudgetTheRestOfTheInterviewDoesNotNeed() {
		assertThat(policy.shouldFollowUp(evaluation(5, true), 0, 20, 2)).isTrue();
		assertThat(policy.shouldFollowUp(evaluation(5, true), 0, 21, 2)).isFalse();
	}

	private static Evaluation evaluation(int score, boolean followUpNeeded) {
		return new Evaluation(score, Scoring.verdict(score), "ok", List.of(), List.of("locking"), List.of(),
				followUpNeeded, followUpNeeded ? "Partly right" : "");
	}

}
