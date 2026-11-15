package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.web.DomainProblems;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns an expected failure of {@link InterviewApiController} into the problem detail of {@link DomainProblems}, so a
 * caller in another service rebuilds the same {@link DomainException} (its {@code DomainErrorHandler}).
 */
@RestControllerAdvice(assignableTypes = InterviewApiController.class)
class InterviewApiErrors {

	@ExceptionHandler(DomainException.class)
	ProblemDetail domainException(DomainException ex) {
		return DomainProblems.toProblem(ex);
	}

}
