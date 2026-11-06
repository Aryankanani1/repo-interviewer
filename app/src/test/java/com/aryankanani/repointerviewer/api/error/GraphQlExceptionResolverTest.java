package com.aryankanani.repointerviewer.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import graphql.GraphQLError;
import graphql.execution.ExecutionId;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Argument;
import graphql.language.Field;
import graphql.language.NullValue;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

/** Task 1.14: each branch of {@link GraphQlExceptionResolver}, with a mocked GraphQL environment. */
@ExtendWith(OutputCaptureExtension.class)
class GraphQlExceptionResolverTest {

	private final GraphQlExceptionResolver resolver = new GraphQlExceptionResolver();

	private final DataFetchingEnvironment env = environment("exec-42", "interviewSession");

	@Test
	void aDomainErrorKeepsItsCodeFieldAndUserMessage() {
		GraphQLError error = resolver.resolveToSingleError(
				new DomainException(ErrorCode.NOT_FOUND, "Project not found.", "projectId"), env);

		assertThat(error.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
		assertThat(error.getMessage()).isEqualTo("Project not found.");
		assertThat(error.getExtensions()).containsEntry("code", "NOT_FOUND").containsEntry("field", List.of("projectId"));
	}

	@Test
	void otherDomainErrorsAreBadRequests() {
		GraphQLError error = resolver.resolveToSingleError(
				new DomainException(ErrorCode.LLM_UNAVAILABLE, "The AI model is not available right now."), env);

		assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
		assertThat(error.getExtensions()).containsEntry("code", "LLM_UNAVAILABLE").doesNotContainKey("field");
	}

	@Test
	void inputThatCannotBeReadIsInvalidInputWithItsField() {
		BindException bind = new BindException(new Object(), "StartInterviewInput");
		bind.addError(new FieldError("StartInterviewInput", "projectId", "not-a-uuid", true, null, null,
				"Failed to convert"));
		DataFetchingEnvironment mutation = environment("exec-42", "startInterview", "input");

		GraphQLError error = resolver.resolveToSingleError(bind, mutation);

		assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
		assertThat(error.getMessage()).isEqualTo("Invalid value for input.projectId.");
		assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT")
				.containsEntry("field", List.of("input", "projectId"));
		assertThat(error.getMessage()).doesNotContain("not-a-uuid", "Failed to convert");
	}

	/** The exception's message may hold SQL or prompt text: it goes to the log, never into the response. */
	@Test
	void anythingElseIsInternalWithACorrelationIdAndNoDetails(CapturedOutput log) {
		IllegalStateException bug = new IllegalStateException("SELECT * FROM answer -- SECRET PROMPT TEXT");

		GraphQLError error = resolver.resolveToSingleError(bug, env);

		assertThat(error.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR);
		assertThat(error.getMessage()).isEqualTo("Something went wrong on our side. Reference: exec-42");
		assertThat(error.getExtensions()).containsEntry("code", "INTERNAL").containsEntry("correlationId", "exec-42");
		assertThat(error.toString()).doesNotContain("SELECT", "SECRET PROMPT TEXT", "IllegalStateException");
		assertThat(log.getOut()).contains("correlationId exec-42", "IllegalStateException",
				"at com.aryankanani.repointerviewer");   // the full stack trace, server side only
	}

	private static DataFetchingEnvironment environment(String executionId, String field, String... arguments) {
		DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
		ExecutionStepInfo step = mock(ExecutionStepInfo.class);
		when(step.getPath()).thenReturn(ResultPath.parse("/" + field));
		when(env.getExecutionStepInfo()).thenReturn(step);
		when(env.getExecutionId()).thenReturn(ExecutionId.from(executionId));
		when(env.getField()).thenReturn(Field.newField(field).arguments(Stream.of(arguments)
				.map(name -> Argument.newArgument(name, NullValue.of()).build()).toList()).build());
		return env;
	}

}
