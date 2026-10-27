package com.aryankanani.repointerviewer.codebase.scan;

/**
 * A scanned file after {@link SecretRedactor}: the only input the chunkers accept, so no code path can store
 * or embed text that skipped redaction.
 *
 * <p>A final class, not a record, on purpose: a record's canonical constructor is as public as the record, so
 * anyone could wrap raw text in one. Here only {@link SecretRedactor} (same package) can create instances.
 */
public final class RedactedFile {

	private final String path;

	private final String language;

	private final String content;

	private final int redactionCount;

	RedactedFile(String path, String language, String content, int redactionCount) {
		this.path = path;
		this.language = language;
		this.content = content;
		this.redactionCount = redactionCount;
	}

	/** Repo-relative with forward slashes, as in {@link ScannedFile#path()}. */
	public String path() {
		return path;
	}

	public String language() {
		return language;
	}

	/** Same number of lines as the original file, so line numbers still match the repository. */
	public String content() {
		return content;
	}

	/** How many secrets were replaced. */
	public int redactionCount() {
		return redactionCount;
	}

	@Override
	public String toString() {
		return "RedactedFile[path=" + path + ", language=" + language + ", redactionCount=" + redactionCount + "]";
	}

}
