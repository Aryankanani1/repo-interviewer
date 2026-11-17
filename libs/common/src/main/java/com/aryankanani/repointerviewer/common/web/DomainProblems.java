package com.aryankanani.repointerviewer.common.web;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.common.error.RemoteModelUnavailableException;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A {@link DomainException} on the wire between the services (task 4.1): an RFC 9457 problem detail with the
 * user-facing message in {@code detail}, the {@link ErrorCode} in {@code code} and the input field in {@code field}.
 * The server writes it ({@link #toProblem}), the caller rebuilds the same exception ({@link #toException}), so an
 * error reads the same after a network hop as before.
 */
public final class DomainProblems {

	public static final String CODE = "code";

	public static final String FIELD = "field";

	private DomainProblems() {
	}

	/** 4xx: the request cannot succeed as it is. 429 and 503: the same request may succeed later. */
	public static HttpStatus status(ErrorCode code) {
		return switch (code) {
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case BUDGET_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
			case LLM_UNAVAILABLE, SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			default -> HttpStatus.UNPROCESSABLE_CONTENT;
		};
	}

	public static ProblemDetail toProblem(DomainException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status(ex.code()), ex.getMessage());
		problem.setProperty(CODE, ex.code().name());
		ex.field().ifPresent(field -> problem.setProperty(FIELD, field));
		return problem;
	}

	/**
	 * @param code the {@code code} of a problem detail; null or unknown when the error did not come from a
	 * {@link DomainException} (a crash, a proxy, a wrong URL)
	 * @return the exception the other service threw, or null if {@code code} is not an {@link ErrorCode}.
	 * LLM_UNAVAILABLE becomes a {@link RemoteModelUnavailableException}, so it is retried like a local one, and
	 * SERVICE_UNAVAILABLE a {@link ServiceUnavailableException} (task 4.2).
	 */
	public static DomainException toException(String code, String detail, String field) {
		if (code == null || Arrays.stream(ErrorCode.values()).noneMatch(known -> known.name().equals(code))) {
			return null;
		}
		ErrorCode errorCode = ErrorCode.valueOf(code);
		return switch (errorCode) {
			case LLM_UNAVAILABLE -> new RemoteModelUnavailableException(detail);
			case SERVICE_UNAVAILABLE -> new ServiceUnavailableException(detail);
			default -> new DomainException(errorCode, detail, field);
		};
	}

}
