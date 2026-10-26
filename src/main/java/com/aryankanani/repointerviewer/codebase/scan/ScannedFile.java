package com.aryankanani.repointerviewer.codebase.scan;

/**
 * One file the analysis keeps, read by {@link FileScanner}. The content is the raw text, before secret
 * redaction (task 1.5), so it must never be logged.
 *
 * @param path repo-relative with forward slashes, for example {@code src/main/java/com/acme/Shop.java}
 * @param language for example {@code java}, {@code yaml}, {@code markdown} (column {@code code_file.language})
 * @param contentSha SHA-256 of the file bytes, lowercase hex
 */
public record ScannedFile(String path, String language, String content, int lineCount, int sizeBytes,
		String contentSha) {

	/** Leaves out the content: it is untrusted and may hold secrets that are not redacted yet. */
	@Override
	public String toString() {
		return "ScannedFile[path=" + path + ", language=" + language + ", lineCount=" + lineCount + ", sizeBytes="
				+ sizeBytes + "]";
	}

}
