package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserError;
import java.util.List;

/** GraphQL type {@code SubmitProjectPayload}: either a project, or errors (never both). */
record SubmitProjectPayload(ProjectView project, List<UserError> errors) {

	static SubmitProjectPayload success(ProjectView project) {
		return new SubmitProjectPayload(project, List.of());
	}

	static SubmitProjectPayload failure(UserError error) {
		return new SubmitProjectPayload(null, List.of(error));
	}

}
