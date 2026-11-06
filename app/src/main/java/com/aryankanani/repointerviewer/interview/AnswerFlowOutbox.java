package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.messaging.AggregateType;
import com.aryankanani.repointerviewer.common.messaging.OutboxWriter;
import com.aryankanani.repointerviewer.common.messaging.ProcessedEvents;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.interview.KafkaAnswerPipeline.AnswerFlowEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The last transaction of each interview worker (task 2.5, LLD §13.5): mark the event processed and write the event
 * that says what happened, worked out from what is stored. The work itself ({@code InterviewService.grade} and
 * {@code advance}) commits before, in its own short transactions, and is safe to run again; so after a crash between
 * the two, the redelivered event finds the work done and only this part runs. A duplicate throws
 * {@code EventAlreadyProcessed}, and nothing here is written.
 */
@Component
@Profile("kafka")
class AnswerFlowOutbox {

	private final ProcessedEvents processed;

	private final OutboxWriter outbox;

	private final AnswerRepository answers;

	private final QuestionRepository questions;

	private final InterviewSessionRepository sessions;

	private final TransactionTemplate transaction;

	AnswerFlowOutbox(ProcessedEvents processed, OutboxWriter outbox, AnswerRepository answers,
			QuestionRepository questions, InterviewSessionRepository sessions,
			PlatformTransactionManager transactionManager) {
		this.processed = processed;
		this.outbox = outbox;
		this.answers = answers;
		this.questions = questions;
		this.sessions = sessions;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** After grading: AnswerEvaluated, if the answer is graded (not when the session ended before it could be). */
	void graded(UUID eventId, String consumer, UUID sessionId, UUID answerId) {
		transaction.executeWithoutResult(status -> {
			processed.claim(eventId, consumer);
			Answer answer = answers.findById(answerId).orElseThrow();
			if (answer.isEvaluated()) {
				write(Topics.ANSWER_EVALUATED, sessionId, KafkaAnswerPipeline.ANSWER_EVALUATED,
						new AnswerFlowEvent(answerId, answer.getQuestionId(), null));
			}
		});
	}

	/**
	 * After the next step: QuestionReady for the question asked right after this answer, else SessionCompleted if the
	 * session completed, else nothing (it was ended or moved on by someone else).
	 */
	void advanced(UUID eventId, String consumer, UUID sessionId, UUID answerId) {
		transaction.executeWithoutResult(status -> {
			processed.claim(eventId, consumer);
			Question answered = questions.findById(answers.findById(answerId).orElseThrow().getQuestionId())
					.orElseThrow();
			Optional<Question> next = questions.findBySessionIdAndOrderIndex(sessionId, answered.getOrderIndex() + 1);
			if (next.isPresent()) {
				write(Topics.QUESTION_READY, sessionId, KafkaAnswerPipeline.QUESTION_READY,
						new AnswerFlowEvent(answerId, answered.getId(), next.get().getId()));
			}
			else if (sessions.findById(sessionId).orElseThrow().getStatus() == SessionStatus.COMPLETED) {
				write(Topics.QUESTION_READY, sessionId, KafkaAnswerPipeline.SESSION_COMPLETED,
						new AnswerFlowEvent(answerId, answered.getId(), null));
			}
		});
	}

	/**
	 * IN_PROGRESS → FAILED with {@code reason}, and SessionFailed. Nothing for a session that is not IN_PROGRESS or
	 * does not exist.
	 *
	 * @param eventId the event to mark processed; null for a dead-lettered one, which is never delivered again
	 * @param answerId the answer that could not be handled, or null when unknown; {@code questionId} likewise
	 */
	void failed(UUID eventId, String consumer, UUID sessionId, UUID answerId, UUID questionId, String reason) {
		transaction.executeWithoutResult(status -> {
			if (eventId != null) {
				processed.claim(eventId, consumer);
			}
			sessions.findById(sessionId).filter(session -> session.getStatus() == SessionStatus.IN_PROGRESS)
					.ifPresent(session -> {
						session.fail(reason);
						write(Topics.QUESTION_READY, sessionId, KafkaAnswerPipeline.SESSION_FAILED,
								new AnswerFlowEvent(answerId, questionId, null));
					});
		});
	}

	private void write(String topic, UUID sessionId, String type, AnswerFlowEvent payload) {
		outbox.write(topic, AggregateType.SESSION, sessionId, type, payload);
	}

}
