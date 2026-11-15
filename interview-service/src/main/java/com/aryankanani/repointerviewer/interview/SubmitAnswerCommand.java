package com.aryankanani.repointerviewer.interview;

import java.util.UUID;

/** Input for {@link InterviewService#submitAnswer}. The text is the candidate's, untrusted. */
public record SubmitAnswerCommand(UUID questionId, String text) {

	/** Leaves out the text. */
	@Override
	public String toString() {
		return "SubmitAnswerCommand[questionId=" + questionId + "]";
	}

}
