package com.aryankanani.repointerviewer.api.hardening;

import com.aryankanani.repointerviewer.common.error.ErrorCode;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;
import org.springframework.graphql.execution.ErrorType;

/**
 * The error for a request refused before any field runs (task 4.6): too deep, too large, or not on the list. A
 * top-level error with {@code extensions.code} INVALID_INPUT, like other input the API cannot accept (LLD §5.2),
 * and no {@code data} at all.
 */
final class InvalidQuery {

	private InvalidQuery() {
	}

	static GraphQLError error(String message) {
		return GraphqlErrorBuilder.newError()
				.errorType(ErrorType.BAD_REQUEST)
				.message(message)
				.extensions(Map.of("code", ErrorCode.INVALID_INPUT.name()))
				.build();
	}

}
