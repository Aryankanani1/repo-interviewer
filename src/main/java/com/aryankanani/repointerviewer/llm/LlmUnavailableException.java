package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;

/**
 * The model API could not be reached or returned an error, after the Anthropic client's own retries. Worth
 * trying again later (Phase 2 retries it with backoff).
 */
public class LlmUnavailableException extends DomainException {

	public LlmUnavailableException(Throwable cause) {
		super(ErrorCode.LLM_UNAVAILABLE, "The AI model is not available right now. Try again in a minute.");
		initCause(cause);
	}

}
