package com.aryankanani.repointerviewer.contracts.interview;

/**
 * What {@link InterviewApi#start} produced: an IN_PROGRESS session with its first question, or a FAILED session
 * with the reason. Once a session exists it is returned even when the start failed, so the client can see it.
 *
 * @param firstQuestion null when the start failed
 * @param error null when the start worked
 */
public record StartResponse(SessionDto session, QuestionDto firstQuestion, ResultError error) {
}
