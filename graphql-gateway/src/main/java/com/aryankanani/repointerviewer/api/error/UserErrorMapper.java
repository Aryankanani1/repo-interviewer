package com.aryankanani.repointerviewer.api.error;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.ResultError;
import java.util.List;

/**
 * Maps expected failures to {@link UserError}. Every mutation has a single argument named
 * {@code input}, so a domain field {@code githubUrl} becomes the path {@code ["input", "githubUrl"]}.
 */
public final class UserErrorMapper {

	private UserErrorMapper() {
	}

	public static UserError toUserError(DomainException ex) {
		List<String> field = ex.field().map(name -> List.of("input", name)).orElse(null);
		return new UserError(ex.code(), ex.getMessage(), field);
	}

	/** A failure after something was saved, as the interview side reports it next to the result (task 4.1). */
	public static UserError toUserError(ResultError error) {
		List<String> field = error.field() == null ? null : List.of("input", error.field());
		return new UserError(ErrorCode.valueOf(error.code()), error.message(), field);
	}

}
