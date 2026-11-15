package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.messaging.AggregateType;
import com.aryankanani.repointerviewer.common.messaging.OutboxWriter;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.contracts.interview.AnswerFlowEvent;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Phase 2 {@link AnswerPipeline} (task 2.5, LLD §13.5): answers are graded and followed up by Kafka workers, linked
 * by events through the outbox, all keyed by session id:
 *
 * <pre>
 * submitAnswer tx:   answer + AnswerSubmitted   → interview.answer.submitted → AnswerEvaluationConsumer
 * after the grade:   AnswerEvaluated            → interview.answer.evaluated → InterviewOrchestrator
 * after the next step: QuestionReady | SessionCompleted | SessionFailed → interview.question.ready
 * </pre>
 *
 * {@code api}'s {@code InterviewEventsFanOut} reads the last two topics for the {@code interviewEvents}
 * subscription.
 */
@Component
@Profile("kafka")
class KafkaAnswerPipeline implements AnswerPipeline {

	private final OutboxWriter outbox;

	KafkaAnswerPipeline(OutboxWriter outbox) {
		this.outbox = outbox;
	}

	@Override
	public void answerSaved(Answer answer, UUID sessionId) {
		outbox.write(Topics.ANSWER_SUBMITTED, AggregateType.SESSION, sessionId, AnswerFlowEvent.ANSWER_SUBMITTED,
				new AnswerFlowEvent(answer.getId(), answer.getQuestionId(), null));
	}

	@Override
	public boolean deferred() {
		return true;
	}

}
