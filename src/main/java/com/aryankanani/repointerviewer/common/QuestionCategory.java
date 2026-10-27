package com.aryankanani.repointerviewer.common;

/**
 * What a question is about. Mirrors the GraphQL enum {@code QuestionCategory} one-to-one (matched by name).
 *
 * <p>Lives in {@code common} because both {@code llm} (the plan output the model returns) and {@code interview}
 * (topics and questions) use it, and {@code llm} may not depend on {@code interview}. As an enum in the output
 * record it also becomes an {@code enum} in the JSON schema, so the model cannot invent a category.
 */
public enum QuestionCategory {
	ARCHITECTURE,
	DESIGN_DECISION,
	DATA_MODEL,
	CONCURRENCY,
	FAILURE_HANDLING,
	SECURITY,
	TESTING,
	PERFORMANCE,
	SCALING,
	CODE_WALKTHROUGH
}
