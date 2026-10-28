package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.interview.Answer;
import com.aryankanani.repointerviewer.interview.AnswerVerdict;
import com.aryankanani.repointerviewer.interview.Question;
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

	/** @param question the answered question, for its ideal outline */
	static AnswerView from(Answer answer, Question question) {
		Evaluation evaluation = answer.getEvaluatedAt() == null ? null
				: new Evaluation(answer.getScore(), answer.getVerdict(), answer.getFeedback(), answer.getCoveredPoints(),
						answer.getMissedPoints(), answer.getIncorrectClaims(), question.getIdealAnswerOutline(),
						answer.getEvaluatedAt().atOffset(ZoneOffset.UTC));
		return new AnswerView(answer.getId(), answer.getText(), answer.getSubmittedAt().atOffset(ZoneOffset.UTC),
				evaluation);
	}

}
