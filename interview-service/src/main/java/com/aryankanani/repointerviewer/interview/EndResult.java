package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;

/**
 * What {@link InterviewService#endInterview} produced: the ended session, and an error when the report could not
 * be written (the session is ended anyway; calling {@code endInterview} again retries the report).
 */
public record EndResult(InterviewSession session, DomainException error) {
}
