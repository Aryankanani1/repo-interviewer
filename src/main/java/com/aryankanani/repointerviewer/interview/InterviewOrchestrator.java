package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.messaging.EventAlreadyProcessed;
import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import com.aryankanani.repointerviewer.common.messaging.EventReader;
import com.aryankanani.repointerviewer.common.messaging.InvalidEventException;
import com.aryankanani.repointerviewer.common.messaging.ProcessedEvents;
import com.aryankanani.repointerviewer.common.messaging.RetryableEventException;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.interview.KafkaAnswerPipeline.AnswerFlowEvent;
import com.aryankanani.repointerviewer.llm.LlmUnavailableException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The interview orchestrator (task 2.5): AnswerEvaluated → {@code InterviewService.advance} (a follow-up, the next
 * topic's question, or COMPLETED and the report) → QuestionReady or SessionCompleted. Failures as in
 * {@link AnswerEvaluationConsumer}. A report that could not be written does not fail the session: it is COMPLETED,
 * and {@code endInterview} writes the report later, as in Phase 1.
 */
@Component
@Profile("kafka")
class InterviewOrchestrator {

	static final String NAME = "interview-orchestrate";

	private static final Logger log = LoggerFactory.getLogger(InterviewOrchestrator.class);

	private final EventReader events;

	private final InterviewService interviewService;

	private final ProcessedEvents processed;

	private final AnswerFlowOutbox outcomes;

	InterviewOrchestrator(EventReader events, InterviewService interviewService, ProcessedEvents processed,
			AnswerFlowOutbox outcomes) {
		this.events = events;
		this.interviewService = interviewService;
		this.processed = processed;
		this.outcomes = outcomes;
	}

	@KafkaListener(topics = Topics.ANSWER_EVALUATED, groupId = NAME)
	void onAnswerEvaluated(String message) {
		EventEnvelope event = events.read(message, KafkaAnswerPipeline.ANSWER_EVALUATED);
		AnswerFlowEvent payload = events.payload(event, AnswerFlowEvent.class);
		if (payload.answerId() == null) {
			throw new InvalidEventException("Event " + event.eventId() + " without answerId");
		}
		UUID sessionId = event.aggregateId();
		MDC.put("sessionId", sessionId.toString());
		try {
			if (processed.isProcessed(event.eventId(), NAME)) {
				return;
			}
			try {
				interviewService.advance(payload.answerId());
			}
			catch (LlmUnavailableException ex) {
				log.warn("Next question not written: model unavailable; the error handler retries or dead-letters it");
				throw new RetryableEventException(ex);
			}
			catch (DomainException ex) {
				log.warn("Next question not written, session FAILED: {}", ex.getMessage());
				outcomes.failed(event.eventId(), NAME, sessionId, payload.answerId(), payload.questionId(),
						ex.getMessage());
				return;
			}
			outcomes.advanced(event.eventId(), NAME, sessionId, payload.answerId());
		}
		catch (EventAlreadyProcessed ex) {
			log.info("Event skipped: {}", ex.getMessage());
		}
		finally {
			MDC.remove("sessionId");
		}
	}

}
