package com.aryankanani.repointerviewer.api.error;

import com.aryankanani.repointerviewer.common.error.DomainException;
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

}
