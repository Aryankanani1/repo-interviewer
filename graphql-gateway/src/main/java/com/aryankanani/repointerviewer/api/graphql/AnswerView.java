package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerVerdict;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** GraphQL type {@code Answer}. {@code evaluation} is null until graded. */
record AnswerView(UUID id, String text, OffsetDateTime submittedAt, Evaluation evaluation) {

	/** GraphQL type {@code AnswerEvaluation}: the ideal outline is shown here, and only here, after answering. */
	record Evaluation(int score, AnswerVerdict verdict, String feedback, List<String> coveredPoints,
			List<String> missedPoints, List<String> incorrectClaims, List<String> idealAnswerOutline,
			OffsetDateTime evaluatedAt) {
	}

	static AnswerView from(AnswerDto answer) {
		AnswerDto.Evaluation graded = answer.evaluation();
		Evaluation evaluation = graded == null ? null
				: new Evaluation(graded.score(), graded.verdict(), graded.feedback(), graded.coveredPoints(),
						graded.missedPoints(), graded.incorrectClaims(), graded.idealAnswerOutline(),
						graded.evaluatedAt().atOffset(ZoneOffset.UTC));
		return new AnswerView(answer.id(), answer.text(), answer.submittedAt().atOffset(ZoneOffset.UTC), evaluation);
	}

}
