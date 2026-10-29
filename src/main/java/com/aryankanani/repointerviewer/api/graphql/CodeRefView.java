package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.interview.CodeRef;
import java.util.UUID;

/**
 * GraphQL type {@code CodeRef}. {@code snippet} is resolved in one batch for every ref of a response
 * ({@link InterviewController#snippet}); {@code sessionId} (not a schema field) tells it which project's code to read.
 */
record CodeRefView(String path, int startLine, int endLine, UUID sessionId) {

	static CodeRefView from(CodeRef ref, UUID sessionId) {
		return new CodeRefView(ref.path(), ref.startLine(), ref.endLine(), sessionId);
	}

}
