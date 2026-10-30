package com.aryankanani.repointerviewer.api.error;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.language.Argument;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

/**
 * Turns every exception a resolver did not handle into a safe top-level GraphQL error (LLD §5.2). Expected failures
 * of mutations never get here: controllers return them in the payload's {@code errors}. Three cases:
 * <ul>
 * <li>A {@link DomainException} that escaped a resolver: its message was written for users, so it is kept, with
 * {@code extensions.code} and {@code extensions.field}.</li>
 * <li>Input Spring could not read, such as an {@code ID} that is not a UUID: {@code INVALID_INPUT} with the field.</li>
 * <li>Anything else is a bug or an outage: {@code INTERNAL}, a fixed message and a {@code correlationId}. The
 * exception's own message can contain SQL, file paths or prompt text, so it never leaves the server: the full
 * stack trace goes to the log under the same id, which is the GraphQL execution id that Spring's own log lines for
 * this request also show.</li>
 * </ul>
 */
@Component
class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

	private static final Logger log = LoggerFactory.getLogger(GraphQlExceptionResolver.class);

	@Override
	protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
		if (ex instanceof DomainException domain) {
			return GraphqlErrorBuilder.newError(env)
					.errorType(domain.code() == ErrorCode.NOT_FOUND ? ErrorType.NOT_FOUND : ErrorType.BAD_REQUEST)
					.message(domain.getMessage())
					.extensions(extensions(domain.code().name(), domain.field().map(List::of).orElse(null)))
					.build();
		}
		if (ex instanceof BindException || ex instanceof TypeMismatchException
				|| ex instanceof ConstraintViolationException) {
			List<String> field = field(ex, env);
			return GraphqlErrorBuilder.newError(env)
					.errorType(ErrorType.BAD_REQUEST)
					.message("Invalid value for " + String.join(".", field) + ".")
					.extensions(extensions(ErrorCode.INVALID_INPUT.name(), field))
					.build();
		}
		String correlationId = env.getExecutionId().toString();
		log.error("Unexpected {} at {} (correlationId {})", ex.getClass().getSimpleName(),
				env.getExecutionStepInfo().getPath(), correlationId, ex);
		return GraphqlErrorBuilder.newError(env)
				.errorType(ErrorType.INTERNAL_ERROR)
				.message("Something went wrong on our side. Reference: " + correlationId)
				.extensions(Map.of("code", ErrorCode.INTERNAL.name(), "correlationId", correlationId))
				.build();
	}

	/**
	 * The argument path of a binding error, for example {@code [id]} or {@code [input, projectId]}. The binder names
	 * the target type and uses {@code $} for a plain value, so the argument name comes from the query itself: our
	 * fields take one argument ({@code id}, or {@code input} for mutations).
	 */
	private static List<String> field(Throwable ex, DataFetchingEnvironment env) {
		List<Argument> arguments = env.getField().getArguments();
		List<String> path = new ArrayList<>(List.of(
				arguments.size() == 1 ? arguments.getFirst().getName() : env.getField().getName()));
		if (ex instanceof BindException bind && bind.getFieldError() != null) {
			String field = bind.getFieldError().getField();
			if (!field.isEmpty() && !field.equals("$")) {
				path.addAll(List.of(field.split("\\.")));
			}
		}
		return path;
	}

	private static Map<String, Object> extensions(String code, List<String> field) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("code", code);
		if (field != null) {
			extensions.put("field", field);
		}
		return extensions;
	}

}
