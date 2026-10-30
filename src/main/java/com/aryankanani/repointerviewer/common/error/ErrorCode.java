package com.aryankanani.repointerviewer.common.error;

/** Expected failure codes. Mirrors the GraphQL enum {@code ErrorCode} one-to-one (matched by name). */
public enum ErrorCode {
	INVALID_URL,
	REPO_NOT_FOUND,
	REPO_TOO_LARGE,
	UNSUPPORTED_LANGUAGE,
	NOT_FOUND,
	PROJECT_NOT_READY,
	INVALID_QUESTION_COUNT,
	SESSION_NOT_ACTIVE,
	NOT_CURRENT_QUESTION,
	ALREADY_ANSWERED,
	ANSWER_EMPTY,
	ANSWER_TOO_LONG,
	BUDGET_EXCEEDED,
	LLM_UNAVAILABLE,
	/** Malformed input: over a size limit, or a value that cannot be read (an ID that is not a UUID). */
	INVALID_INPUT,
	/** A bug or an outage. Only in top-level GraphQL errors, never in a payload. */
	INTERNAL
}
