package com.aryankanani.repointerviewer.llm;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

	/** Calls since a moment, for the daily budget. Served by the index {@code ix_llm_call_created}. */
	long countByCreatedAtGreaterThanEqual(Instant since);

}
