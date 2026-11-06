package com.aryankanani.repointerviewer.llm;

/**
 * Why a model is called. Each purpose has its own prompt template ({@code prompts/<template>.st}), model and
 * output-token limit ({@code app.llm.models}, {@code app.llm.max-output-tokens}). Mirrors the
 * {@code ck_llm_purpose} check on {@code llm_call.purpose} one-to-one (matched by name).
 */
public enum LlmPurpose {

	SUMMARY("repo-summary"),
	PLAN("interview-plan"),
	QUESTION("question-generation"),
	FOLLOW_UP("follow-up-question"),
	EVALUATION("answer-evaluation"),
	REPORT("session-report");

	private final String template;

	LlmPurpose(String template) {
		this.template = template;
	}

	/** The prompt file name without {@code .st}. Fixed per purpose, so the two can never disagree. */
	public String template() {
		return template;
	}

}
