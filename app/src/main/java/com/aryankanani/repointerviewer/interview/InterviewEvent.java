package com.aryankanani.repointerviewer.interview;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to a session on Kafka (task 2.5), for the {@code interviewEvents} subscription. Only ids:
 * the subscription reads the answer, the question and the session from the database when it sends the event.
 *
 * @param questionId the answered question; null for SESSION_FAILED from a dead-lettered event
 * @param nextQuestionId the question just asked, for QUESTION_READY only
 */
public record InterviewEvent(UUID sessionId, InterviewEventType type, UUID questionId, UUID nextQuestionId,
		Instant at) {
}
