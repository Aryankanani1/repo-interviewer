package com.aryankanani.repointerviewer.contracts.interview;

/** Input of {@link InterviewApi#submitAnswer}. The text is the candidate's, untrusted. */
public record AnswerRequest(String text) {

	/** Leaves out the text. */
	@Override
	public String toString() {
		return "AnswerRequest[text=" + (text == null ? 0 : text.length()) + " chars]";
	}

}
