package com.aryankanani.repointerviewer.api.graphql;

/** GraphQL input {@code SubmitProjectInput}. Bound by Spring for GraphQL through the record constructor. */
record SubmitProjectInput(String githubUrl, String goal, String domain, String targetRole) {
}
