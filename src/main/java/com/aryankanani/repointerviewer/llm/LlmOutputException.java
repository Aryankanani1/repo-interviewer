package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;

/**
 * The model answered, but twice with output that could not be read into the requested record. Retrying the
 * same request is unlikely to help (Phase 2 sends it straight to the dead-letter topic).
 */
public class LlmOutputException extends DomainException {

	public LlmOutputException(Throwable cause) {
		super(ErrorCode.LLM_UNAVAILABLE, "The AI model returned an answer that could not be used. Try again.");
		initCause(cause);
	}

}
