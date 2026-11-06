package com.aryankanani.repointerviewer.interview;

/**
 * The grade band of an answer, decided by code from the score ({@code Scoring.verdict}), never by the model.
 * Mirrors the GraphQL enum {@code AnswerVerdict} and the {@code ck_answer_verdict} check.
 */
public enum AnswerVerdict {
	STRONG,
	ADEQUATE,
	WEAK,
	OFF_TOPIC
}
