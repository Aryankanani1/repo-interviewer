package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.web.DomainProblems;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns an expected failure of {@link AnalysisApiController} into the problem detail of {@link DomainProblems}, so a
 * caller in another service rebuilds the same {@link DomainException} (its {@code DomainErrorHandler}).
 */
@RestControllerAdvice(assignableTypes = AnalysisApiController.class)
class AnalysisApiErrors {

	@ExceptionHandler(DomainException.class)
	ProblemDetail domainException(DomainException ex) {
		return DomainProblems.toProblem(ex);
	}

}
