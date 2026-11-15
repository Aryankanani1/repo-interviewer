package com.aryankanani.repointerviewer.codebase.embed;

import com.aryankanani.repointerviewer.common.error.ModelUnavailableException;

/**
 * The embedding model (Ollama) could not be reached or answered with an error, after Spring AI's own retries
 * (task 3.6). Retried like an unavailable chat model; found in the 3.4 load test, where a pod could not resolve
 * Ollama's host for a moment and the interview ended.
 */
public class EmbeddingUnavailableException extends ModelUnavailableException {

	public EmbeddingUnavailableException(Throwable cause) {
		super("The code search model is not available right now. Try again in a minute.", cause);
	}

}
