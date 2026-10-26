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
	INTERNAL
}
