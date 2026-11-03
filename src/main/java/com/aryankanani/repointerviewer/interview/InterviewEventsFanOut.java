package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.messaging.EventEnvelope;
import com.aryankanani.repointerviewer.common.messaging.EventReader;
import com.aryankanani.repointerviewer.common.messaging.InstanceId;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import com.aryankanani.repointerviewer.interview.KafkaAnswerPipeline.AnswerFlowEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds this instance's {@code interviewEvents} subscriptions (task 2.5) from the two topics a client cares about:
 * the grade, then the next step.
 *
 * <p>A consumer group of its own <b>per app instance</b> (task 2.6, {@link InstanceId}): a group gives each event to
 * one of its members, which is right for workers but would send a session's events to one instance only, while its
 * client may be connected to another. With one group each, every instance reads every event and keeps its own
 * subscribers' ones. The group starts at the newest event ({@code latest}), not the oldest: it is new at every start,
 * and a live view has no use for the history.
 *
 * <p>Best effort: it never throws. A subscriber that misses an event still finds everything with
 * {@code interviewSession}, and an event of this group must never reach the dead-letter topic, where
 * {@link InterviewRetryListener} would fail the session for it.
 */
@Component
@Profile("kafka")
class InterviewEventsFanOut {

	/** The listener container's id, for tests that wait for its partitions. Not the group id. */
	static final String LISTENER_ID = "interview-events";

	private static final Logger log = LoggerFactory.getLogger(InterviewEventsFanOut.class);

	private static final Map<String, InterviewEventType> TYPES = Map.of(
			KafkaAnswerPipeline.ANSWER_EVALUATED, InterviewEventType.ANSWER_EVALUATED,
			KafkaAnswerPipeline.QUESTION_READY, InterviewEventType.QUESTION_READY,
			KafkaAnswerPipeline.SESSION_COMPLETED, InterviewEventType.SESSION_COMPLETED,
			KafkaAnswerPipeline.SESSION_FAILED, InterviewEventType.SESSION_FAILED);

	private final EventReader events;

	private final InterviewEventStream stream;

	InterviewEventsFanOut(EventReader events, InterviewEventStream stream) {
		this.events = events;
		this.stream = stream;
	}

	@KafkaListener(id = LISTENER_ID, idIsGroup = false, groupId = "interview-events-#{@instanceId.value()}",
			topics = { Topics.ANSWER_EVALUATED, Topics.QUESTION_READY }, properties = "auto.offset.reset=latest")
	void onEvent(String message) {
		try {
			EventEnvelope event = events.read(message, TYPES.keySet().toArray(String[]::new));
			AnswerFlowEvent payload = events.payload(event, AnswerFlowEvent.class);
			stream.publish(new InterviewEvent(event.aggregateId(), TYPES.get(event.type()), payload.questionId(),
					payload.nextQuestionId(), Objects.requireNonNullElseGet(event.occurredAt(), Instant::now)));
		}
		catch (RuntimeException ex) {
			log.warn("Interview event not sent to subscribers: {}", ex.getMessage());
		}
	}

}
