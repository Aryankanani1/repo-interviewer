package com.aryankanani.repointerviewer.interview;

import java.util.UUID;

/**
 * What happens after {@code submitAnswer} saved a new answer (task 2.5). Phase 1 ({@link InProcessAnswerPipeline}):
 * the request grades it and writes the next question itself. Phase 2 ({@link KafkaAnswerPipeline}, {@code kafka}
 * profile): an event, and workers do the rest. {@link InterviewService} is the same for both (DECISIONS.md D-001).
 */
interface AnswerPipeline {

	/** Runs inside the transaction that inserts the answer, so what it writes commits with the answer or not at all. */
	void answerSaved(Answer answer, UUID sessionId);

	/** True when grading and the next step happen later, in workers: {@code submitAnswer} returns it ungraded. */
	boolean deferred();

}
