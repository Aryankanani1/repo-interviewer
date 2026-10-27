package com.aryankanani.repointerviewer.codebase.chunk;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.codebase.scan.ScannedFile;
import com.aryankanani.repointerviewer.codebase.scan.SecretRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Files for chunker tests. A {@link RedactedFile} can only come from {@link SecretRedactor}, so tests go through
 * it exactly like production does.
 */
final class TestFiles {

	private static final SecretRedactor REDACTOR = new SecretRedactor();

	private TestFiles() {
	}

	/** @param path a path from {@link LocalDirectoryRepoFetcher#FIXTURE_FILES} */
	static RedactedFile fixture(String path) {
		try {
			return text(path, Files.readString(LocalDirectoryRepoFetcher.fixtureRoot().resolve(path)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static RedactedFile text(String path, String content) {
		return REDACTOR.redact(new ScannedFile(path, languageOf(path), content, (int) content.lines().count(),
				content.length(), "unused"));
	}

	/** Same values as the scanner's language detection, for the extensions these tests use. */
	static String languageOf(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		if (name.equals("Dockerfile")) {
			return "dockerfile";
		}
		return switch (name.substring(name.lastIndexOf('.') + 1)) {
			case "java" -> "java";
			case "yml", "yaml" -> "yaml";
			case "properties" -> "properties";
			case "sql" -> "sql";
			case "md" -> "markdown";
			case "xml" -> "xml";
			default -> "text";
		};
	}

}
