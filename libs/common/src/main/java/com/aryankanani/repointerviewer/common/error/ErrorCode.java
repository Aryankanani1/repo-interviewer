package com.aryankanani.repointerviewer.common.error;

/** Expected failure codes. Mirrors the GraphQL enum {@code ErrorCode} one-to-one (matched by name). */
public enum ErrorCode {
	INVALID_URL,
	REPO_NOT_FOUND,
	REPO_TOO_LARGE,
	UNSUPPORTED_LANGUAGE,
	NOT_FOUND,
	/** It exists, but belongs to another user (task 4.4): the caller may not read or change it. */
	FORBIDDEN,
	/** Too many expensive requests by this user in a short time (task 4.4): a token bucket is empty. */
	RATE_LIMITED,
	PROJECT_NOT_READY,
	INVALID_QUESTION_COUNT,
	SESSION_NOT_ACTIVE,
	NOT_CURRENT_QUESTION,
	ALREADY_ANSWERED,
	ANSWER_EMPTY,
	ANSWER_TOO_LONG,
	BUDGET_EXCEEDED,
	LLM_UNAVAILABLE,
	/** Another service of this system did not answer (task 4.2): the same request may work in a minute. */
	SERVICE_UNAVAILABLE,
	/** Malformed input: over a size limit, or a value that cannot be read (an ID that is not a UUID). */
	INVALID_INPUT,
	/** A bug or an outage. Only in top-level GraphQL errors, never in a payload. */
	INTERNAL
}
