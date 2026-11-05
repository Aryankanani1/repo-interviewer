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
 * The evaluation worker (task 2.5): AnswerSubmitted → {@code InterviewService.grade} (the EVALUATION call) →
 * AnswerEvaluated. While the model is unavailable the event is retried, then dead-lettered (task 2.4); a failure that
 * would repeat (budget, an unusable reply) fails the session at once.
 */
@Component
@Profile("kafka")
class AnswerEvaluationConsumer {

	static final String NAME = "interview-evaluate";

	private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationConsumer.class);

	private final EventReader events;

	private final InterviewService interviewService;

	private final ProcessedEvents processed;

	private final AnswerFlowOutbox outcomes;

	AnswerEvaluationConsumer(EventReader events, InterviewService interviewService, ProcessedEvents processed,
			AnswerFlowOutbox outcomes) {
		this.events = events;
		this.interviewService = interviewService;
		this.processed = processed;
		this.outcomes = outcomes;
	}

	@KafkaListener(topics = Topics.ANSWER_SUBMITTED, groupId = NAME, autoStartup = "${app.roles.interview-worker}")
	void onAnswerSubmitted(String message) {
		EventEnvelope event = events.read(message, KafkaAnswerPipeline.ANSWER_SUBMITTED);
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
				interviewService.grade(payload.answerId());
			}
			catch (LlmUnavailableException ex) {
				log.warn("Answer not graded: model unavailable; the error handler retries or dead-letters it");
				throw new RetryableEventException(ex);
			}
			catch (DomainException ex) {
				log.warn("Answer not graded, session FAILED: {}", ex.getMessage());
				outcomes.failed(event.eventId(), NAME, sessionId, payload.answerId(), payload.questionId(),
						ex.getMessage());
				return;
			}
			outcomes.graded(event.eventId(), NAME, sessionId, payload.answerId());
		}
		catch (EventAlreadyProcessed ex) {
			log.info("Event skipped: {}", ex.getMessage());
		}
		finally {
			MDC.remove("sessionId");
		}
	}

}
