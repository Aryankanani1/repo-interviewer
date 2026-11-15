package com.aryankanani.repointerviewer.contracts.interview;

/**
 * What {@link InterviewApi#end} produced: the ended session, and an error when the report could not be written (the
 * session is ended anyway; ending it again retries the report).
 */
public record EndResponse(SessionDto session, ResultError error) {
}
