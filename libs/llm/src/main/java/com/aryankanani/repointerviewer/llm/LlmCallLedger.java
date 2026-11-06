package com.aryankanani.repointerviewer.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Writes one {@code llm_call} row per model call attempt, success or failure (LLD §8.1 step 6). */
@Component
class LlmCallLedger {

	private static final Logger log = LoggerFactory.getLogger(LlmCallLedger.class);

	private final LlmCallRepository repository;

	LlmCallLedger(LlmCallRepository repository) {
		this.repository = repository;
	}

	/**
	 * Never throws. The model call already happened (and was paid for); if the row cannot be written, that is
	 * logged as an error, but the caller still gets the call's real result instead of a database error.
	 */
	void record(LlmCall call) {
		try {
			repository.save(call);
		}
		catch (RuntimeException ex) {
			log.error("Could not write the llm_call row for {} attempt {}", call.getPurpose(), call.getAttempt(), ex);
		}
	}

}
