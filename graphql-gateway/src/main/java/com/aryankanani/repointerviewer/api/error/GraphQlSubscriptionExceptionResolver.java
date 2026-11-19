package com.aryankanani.repointerviewer.api.error;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.stereotype.Component;

/**
 * The subscription half of {@link GraphQlExceptionResolver} (task 4.4): an exception that ends a subscription's
 * stream, such as FORBIDDEN for another user's interview, reaches the client as one GraphQL error in the same safe
 * shape. A {@link DomainException} keeps its message and code; anything else is INTERNAL with a correlation id, its
 * details only in the log. Without this, Spring's default would name the exception's class.
 */
@Component
class GraphQlSubscriptionExceptionResolver extends SubscriptionExceptionResolverAdapter {

	private static final Logger log = LoggerFactory.getLogger(GraphQlSubscriptionExceptionResolver.class);

	@Override
	protected GraphQLError resolveToSingleError(Throwable ex) {
		if (ex instanceof DomainException domain) {
			return GraphqlErrorBuilder.newError().errorType(GraphQlExceptionResolver.errorType(domain.code()))
					.message(domain.getMessage()).extensions(Map.of("code", domain.code().name())).build();
		}
		String correlationId = UUID.randomUUID().toString();
		log.error("Unexpected {} in a subscription (correlationId {})", ex.getClass().getSimpleName(), correlationId,
				ex);
		return GraphqlErrorBuilder.newError().errorType(ErrorType.INTERNAL_ERROR)
				.message("Something went wrong on our side. Reference: " + correlationId)
				.extensions(Map.of("code", ErrorCode.INTERNAL.name(), "correlationId", correlationId)).build();
	}

}
