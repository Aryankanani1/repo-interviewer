package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserError;
import java.util.List;
import java.util.UUID;

/** GraphQL type {@code DeleteProjectPayload} (task 4.4): the deleted project's id, or errors (never both). */
record DeleteProjectPayload(UUID deletedProjectId, List<UserError> errors) {

	static DeleteProjectPayload success(UUID projectId) {
		return new DeleteProjectPayload(projectId, List.of());
	}

	static DeleteProjectPayload failure(UserError error) {
		return new DeleteProjectPayload(null, List.of(error));
	}

}
