package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserError;
import java.util.List;

/**
 * GraphQL type {@code SubmitAnswerPayload}. The answer can come with an error: it was saved, but grading it or
 * preparing the next question failed. Sending the same text again continues from there.
 */
record SubmitAnswerPayload(AnswerView answer, QuestionView nextQuestion, InterviewSessionView session,
		List<UserError> errors) {
}
