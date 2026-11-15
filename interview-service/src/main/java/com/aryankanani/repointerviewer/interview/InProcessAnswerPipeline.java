package com.aryankanani.repointerviewer.interview;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Phase 1 {@link AnswerPipeline}: nothing to send, {@code submitAnswer} grades and moves on in the request. */
@Component
@Profile("!kafka")   // the kafka profile uses KafkaAnswerPipeline (task 2.5, DECISIONS.md D-016)
class InProcessAnswerPipeline implements AnswerPipeline {

	@Override
	public void answerSaved(Answer answer, UUID sessionId) {
	}

	@Override
	public boolean deferred() {
		return false;
	}

}
