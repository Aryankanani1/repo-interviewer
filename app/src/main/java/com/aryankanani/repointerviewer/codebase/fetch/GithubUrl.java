package com.aryankanani.repointerviewer.codebase.fetch;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A validated public GitHub repository address: {@code https://github.com/{owner}/{repo}}.
 *
 * <p>Security boundary: the raw user input is never passed to JGit. Only the two validated parts
 * are kept, and the clone URL is rebuilt from them, so input like {@code https://github.com.evil.com/...},
 * {@code https://user@github.com/...} or {@code ../} tricks cannot reach another host or path (SSRF).
 *
 * <p>Owner and repo are lowercased: GitHub treats {@code Acme/Shop} and {@code acme/shop} as the same
 * repository, and the (url, commit) duplicate check must too.
 */
public record GithubUrl(String owner, String repo) {

	private static final String FIELD = "githubUrl";

	private static final String HINT = "Use a public repository URL like https://github.com/owner/repo";

	/** Scheme and host are case-insensitive; a trailing ".git" and/or "/" are allowed. Nothing else. */
	private static final Pattern URL = Pattern.compile(
			"https://github\\.com/([A-Za-z0-9._-]{1,100})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern PART = Pattern.compile("[a-z0-9._-]{1,100}");

	public GithubUrl {
		if (!isValidPart(owner) || !isValidPart(repo)) {
			throw invalid();
		}
	}

	public static GithubUrl parse(String raw) {
		if (raw == null) {
			throw invalid();
		}
		Matcher matcher = URL.matcher(raw.strip());
		if (!matcher.matches()) {
			throw invalid();
		}
		return new GithubUrl(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).toLowerCase(Locale.ROOT));
	}

	/** The stored, deduplicated form: {@code https://github.com/acme/shop}. */
	public String normalized() {
		return "https://github.com/" + owner + "/" + repo;
	}

	/** Rebuilt from the validated parts, never taken from the input. */
	public String cloneUri() {
		return normalized() + ".git";
	}

	/** {@code acme/shop}. */
	public String fullName() {
		return owner + "/" + repo;
	}

	private static boolean isValidPart(String part) {
		// "." and ".." match the character class but would point at the parent folder in a file path.
		return part != null && PART.matcher(part).matches() && !part.equals(".") && !part.equals("..");
	}

	private static DomainException invalid() {
		return new DomainException(ErrorCode.INVALID_URL, HINT, FIELD);
	}

}
