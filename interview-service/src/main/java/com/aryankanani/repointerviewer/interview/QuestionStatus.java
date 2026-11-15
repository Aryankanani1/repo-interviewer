package com.aryankanani.repointerviewer.interview;

/**
 * PREPARED (generated, not shown yet) → ASKED → ANSWERED, or ASKED → SKIPPED when the session is abandoned
 * (LLD §3.2). Only ASKED and later are visible to the candidate.
 */
public enum QuestionStatus {
	PREPARED,
	ASKED,
	ANSWERED,
	SKIPPED
}
