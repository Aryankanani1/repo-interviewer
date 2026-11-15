package com.aryankanani.repointerviewer.contracts.interview;

/**
 * What a question is about. Mirrors the GraphQL enum {@code QuestionCategory} one-to-one (matched by name).
 *
 * <p>Lives in {@code contracts} because {@code llm} (the plan output the model returns), {@code interview} (topics and
 * questions) and the callers of {@code InterviewApi} all use it, and {@code llm} may not depend on {@code interview}
 * (it was in {@code common} until task 4.1). As an enum in the output
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
