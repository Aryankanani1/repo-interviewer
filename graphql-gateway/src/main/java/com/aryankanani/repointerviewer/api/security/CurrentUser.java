package com.aryankanani.repointerviewer.api.security;

import java.security.Principal;

/**
 * The caller's user id (task 4.4): the JWT's {@code sub} claim, which Spring Security makes the principal's name.
 * Controllers take the {@link Principal} as a method argument; Spring for GraphQL resolves it from the security
 * context, which follows the request onto the virtual thread that runs the controller.
 */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static String id(Principal principal) {
		if (principal == null) {
			// SecurityConfiguration lets no unauthenticated request reach GraphQL, so this is a bug, not a user error.
			throw new IllegalStateException("No authenticated user for this request");
		}
		return principal.getName();
	}

}
