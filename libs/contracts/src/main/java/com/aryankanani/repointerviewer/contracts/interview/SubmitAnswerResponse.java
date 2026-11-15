package com.aryankanani.repointerviewer.contracts.interview;

/**
 * What {@link InterviewApi#submitAnswer} produced. The answer is saved before anything can fail, so it comes back
 * even with an {@code error}; sending the same text again continues from where it stopped.
 *
 * @param answer with its evaluation, unless grading is done later (Kafka) or failed
 * @param nextQuestion the question asked after this answer; null when the session completed, when the next step
 *        runs later (Kafka), or when it failed ({@code error})
 * @param error null when every step worked, for example LLM_UNAVAILABLE when grading or the next question failed
 */
public record SubmitAnswerResponse(AnswerDto answer, QuestionDto nextQuestion, SessionDto session,
		ResultError error) {
}
