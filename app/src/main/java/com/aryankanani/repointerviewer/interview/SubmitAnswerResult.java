package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;

/**
 * What {@link InterviewService#submitAnswer} produced. The answer is always saved before anything can fail, so it
 * comes back even with an {@code error}; sending the same text again continues from where it stopped.
 *
 * @param question the answered question (its ideal outline is shown with the evaluation)
 * @param nextQuestion the question asked after this answer; null when the session completed, or when the next step
 *        failed ({@code error})
 * @param error null when every step worked, for example LLM_UNAVAILABLE when grading or the next question failed
 */
public record SubmitAnswerResult(Answer answer, Question question, Question nextQuestion, InterviewSession session,
		DomainException error) {
}
