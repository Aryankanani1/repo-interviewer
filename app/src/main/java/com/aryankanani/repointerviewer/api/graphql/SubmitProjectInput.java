package com.aryankanani.repointerviewer.api.graphql;

import jakarta.validation.constraints.Size;

/**
 * GraphQL input {@code SubmitProjectInput}. Bound by Spring for GraphQL through the record constructor. The sizes are
 * outer limits against absurd payloads ({@code InputValidator}); the URL rules live in {@code GithubUrl}, and the
 * context fields are trimmed to 500 characters by {@code SubmitProjectCommand}.
 */
record SubmitProjectInput(
		@Size(max = 300, message = "must be at most 300 characters") String githubUrl,
		@Size(max = 2000, message = "must be at most 2000 characters") String goal,
		@Size(max = 2000, message = "must be at most 2000 characters") String domain,
		@Size(max = 2000, message = "must be at most 2000 characters") String targetRole) {
}
