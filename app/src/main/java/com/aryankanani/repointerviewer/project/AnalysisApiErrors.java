package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns an expected failure of {@link AnalysisApiController} into an RFC 9457 problem detail: the user-facing message
 * in {@code detail}, the {@link ErrorCode} in {@code code} and the input field in {@code field}, so a caller can
 * rebuild the same {@link DomainException}. Only this controller: GraphQL errors have their own resolver.
 */
@RestControllerAdvice(assignableTypes = AnalysisApiController.class)
class AnalysisApiErrors {

	@ExceptionHandler(DomainException.class)
	ProblemDetail domainException(DomainException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status(ex.code()), ex.getMessage());
		problem.setProperty("code", ex.code().name());
		ex.field().ifPresent(field -> problem.setProperty("field", field));
		return problem;
	}

	/** 4xx: the request cannot succeed as it is. 429 and 503: the same request may succeed later. */
	static HttpStatus status(ErrorCode code) {
		return switch (code) {
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case BUDGET_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
			case LLM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			default -> HttpStatus.UNPROCESSABLE_CONTENT;
		};
	}

}
