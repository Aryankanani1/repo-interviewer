package com.aryankanani.repointerviewer.interview;

/**
 * Where an interview is (LLD §3.2). Mirrors the GraphQL enum {@code SessionStatus} and the {@code ck_session_status}
 * check one-to-one (matched by name).
 */
public enum SessionStatus {
	PLANNING,
	IN_PROGRESS,
	COMPLETED,
	ABANDONED,
	FAILED
}
