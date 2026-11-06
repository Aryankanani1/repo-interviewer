package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.common.error.ModelUnavailableException;

/**
 * The model API could not be reached or returned an error, after the Anthropic client's own retries. Worth
 * trying again later (Phase 2 retries it with backoff).
 */
public class LlmUnavailableException extends ModelUnavailableException {

	public LlmUnavailableException(Throwable cause) {
		super("The AI model is not available right now. Try again in a minute.", cause);
	}

}
