package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;

/**
 * What {@link InterviewService#start} produced. Either an IN_PROGRESS session with its first question, or a
 * FAILED session with the reason as {@code error}: once a session row exists it is returned even when the start
 * failed, so the client can still see it.
 *
 * @param firstQuestion null when the start failed
 * @param error null when the start worked
 */
public record StartResult(InterviewSession session, Question firstQuestion, DomainException error) {
}
