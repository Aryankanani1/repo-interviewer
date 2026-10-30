package com.aryankanani.repointerviewer.api.graphql;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * GraphQL input {@code SubmitAnswerInput}. The size is an outer limit against absurd payloads ({@code InputValidator});
 * the real limit, {@code app.interview.answer-max-chars} (4000), is the service's {@code ANSWER_TOO_LONG}.
 */
record SubmitAnswerInput(UUID questionId, @Size(max = 20_000, message = "must be at most 20000 characters") String text) {

	/** Leaves out the text: it is the candidate's untrusted answer. */
	@Override
	public String toString() {
		return "SubmitAnswerInput[questionId=" + questionId + "]";
	}

}
