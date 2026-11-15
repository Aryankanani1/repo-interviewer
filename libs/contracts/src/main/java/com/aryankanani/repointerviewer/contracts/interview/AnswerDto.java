package com.aryankanani.repointerviewer.contracts.interview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The candidate's answer to a question (task 4.1). The text is the candidate's, untrusted.
 *
 * @param evaluation null until the answer is graded
 */
public record AnswerDto(UUID id, UUID questionId, String text, Instant submittedAt, Evaluation evaluation) {

	/**
	 * The grade, and the question's ideal answer outline, which is shown only once the answer is graded.
	 *
	 * @param score 0 to 10
	 */
	public record Evaluation(int score, AnswerVerdict verdict, String feedback, List<String> coveredPoints,
			List<String> missedPoints, List<String> incorrectClaims, List<String> idealAnswerOutline,
			Instant evaluatedAt) {
	}

	/** Leaves out the text. */
	@Override
	public String toString() {
		return "AnswerDto[id=" + id + ", questionId=" + questionId + ", evaluation=" + evaluation + "]";
	}

}
