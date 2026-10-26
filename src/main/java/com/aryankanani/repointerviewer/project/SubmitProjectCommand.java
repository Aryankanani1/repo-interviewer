package com.aryankanani.repointerviewer.project;

/**
 * Input for {@link ProjectService#submit}. The context fields are the candidate's own words
 * (untrusted text): trimmed, blank becomes null, and cut to {@value #MAX_CONTEXT_CHARS} characters.
 */
public record SubmitProjectCommand(String githubUrl, String goal, String domain, String targetRole) {

	static final int MAX_CONTEXT_CHARS = 500;

	public SubmitProjectCommand {
		goal = cleanContext(goal);
		domain = cleanContext(domain);
		targetRole = cleanContext(targetRole);
	}

	private static String cleanContext(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String trimmed = text.strip();
		return trimmed.length() <= MAX_CONTEXT_CHARS ? trimmed : trimmed.substring(0, MAX_CONTEXT_CHARS);
	}

}
