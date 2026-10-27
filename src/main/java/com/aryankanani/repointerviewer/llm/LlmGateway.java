package com.aryankanani.repointerviewer.llm;

import com.aryankanani.repointerviewer.llm.output.LlmOutput;

/**
 * The only way to call a chat model (CLAUDE.md). Never call it inside a database transaction, and never while
 * resolving a GraphQL field on read.
 */
public interface LlmGateway {

	/**
	 * Calls the model for the request's purpose and returns its output, already normalized
	 * ({@link LlmOutput#normalized()}).
	 *
	 * @throws BudgetExceededException the daily or the session limit is used up; the model was not called
	 * @throws LlmUnavailableException the API could not be reached or returned an error, after retries
	 * @throws LlmOutputException the model's output could not be used, twice
	 */
	<T extends LlmOutput<T>> T call(LlmRequest<T> request);

}
