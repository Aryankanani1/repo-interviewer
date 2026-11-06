package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserError;
import java.util.List;

/**
 * GraphQL type {@code StartInterviewPayload}. Unlike {@code SubmitProjectPayload}, a session and an error can come
 * together: a start that failed after the session was created returns that FAILED session with the reason.
 */
record StartInterviewPayload(InterviewSessionView session, QuestionView firstQuestion, List<UserError> errors) {
}
