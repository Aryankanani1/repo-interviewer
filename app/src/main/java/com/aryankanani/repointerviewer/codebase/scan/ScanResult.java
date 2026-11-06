package com.aryankanani.repointerviewer.codebase.scan;

import java.util.List;

/**
 * What {@link FileScanner#scan} found.
 *
 * @param files the files to analyze, sorted by path
 * @param skipped paths left out for a reason worth reporting (safety or limits), sorted by path. Ordinary
 *        files that are simply not source (images, {@code .txt}, ...) are not listed.
 */
public record ScanResult(List<ScannedFile> files, List<Skipped> skipped) {

	public record Skipped(String path, Reason reason) {
	}

	public enum Reason {
		/** Build output, dependencies, IDE or VCS folder. Listed once per folder; nothing inside is read. */
		IGNORED_DIRECTORY,
		/** {@code .env}, private keys, keystores, credentials. Never read. */
		SECRET_FILE,
		/** A symbolic link, to a file or a folder. Never followed: it could point outside the clone. */
		SYMLINK,
		/** Larger than {@code app.repo.max-file-bytes}. */
		TOO_LARGE,
		/** A NUL byte in the first 8 KB: not text. */
		BINARY
	}

}
