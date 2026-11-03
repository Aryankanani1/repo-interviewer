package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.interview.InterviewEventType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GraphQL type {@code InterviewEvent} (task 2.5). {@code answer}, {@code nextQuestion} and {@code session} are read
 * from the database when the event is sent, so they show the state at that moment; their nested fields use the same
 * batch resolvers as a query.
 */
record InterviewEventView(UUID sessionId, InterviewEventType type, AnswerView answer, QuestionView nextQuestion,
		InterviewSessionView session, OffsetDateTime at) {
}
