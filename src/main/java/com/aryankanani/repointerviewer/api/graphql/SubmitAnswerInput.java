package com.aryankanani.repointerviewer.api.graphql;

import java.util.UUID;

/** GraphQL input {@code SubmitAnswerInput}. */
record SubmitAnswerInput(UUID questionId, String text) {

	/** Leaves out the text: it is the candidate's untrusted answer. */
	@Override
	public String toString() {
		return "SubmitAnswerInput[questionId=" + questionId + "]";
	}

}
