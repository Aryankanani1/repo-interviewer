package com.aryankanani.repointerviewer.codebase.chunk;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.codebase.scan.ScannedFile;
import com.aryankanani.repointerviewer.codebase.scan.SecretRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Files for chunker and repo map tests. A {@link RedactedFile} can only come from {@link SecretRedactor}, so tests
 * go through it exactly like production does.
 */
public final class TestFiles {

	private static final SecretRedactor REDACTOR = new SecretRedactor();

	private TestFiles() {
	}

	/** @param path a path from {@link LocalDirectoryRepoFetcher#FIXTURE_FILES} */
	public static RedactedFile fixture(String path) {
		return REDACTOR.redact(scanned(path));
	}

	/** The fixture file before redaction, as the scanner returns it (for example to classify it). */
	public static ScannedFile scanned(String path) {
		try {
			return scannedText(path, Files.readString(LocalDirectoryRepoFetcher.fixtureRoot().resolve(path)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static RedactedFile text(String path, String content) {
		return REDACTOR.redact(scannedText(path, content));
	}

	private static ScannedFile scannedText(String path, String content) {
		return new ScannedFile(path, languageOf(path), content, (int) content.lines().count(), content.length(),
				"unused");
	}

	/** Same values as the scanner's language detection, for the extensions these tests use. */
	public static String languageOf(String path) {
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
