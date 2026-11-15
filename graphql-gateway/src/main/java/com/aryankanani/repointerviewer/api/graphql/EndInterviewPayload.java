package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserError;
import java.util.List;

/** GraphQL type {@code EndInterviewPayload}: the ended session, with an error if its report could not be written. */
record EndInterviewPayload(InterviewSessionView session, List<UserError> errors) {
}
