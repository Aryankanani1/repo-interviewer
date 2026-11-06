package com.aryankanani.repointerviewer.codebase.fetch;

import java.nio.file.Path;

/**
 * A repository checked out in its own temporary directory. The caller owns the directory and closes this
 * to delete it, ideally with try-with-resources: clones are untrusted and never outlive the analysis.
 *
 * @param directory the working tree root (it contains {@code .git})
 * @param commitSha the commit actually checked out. Usually the one asked for; a newer one if the branch
 *        moved between {@code resolveHead} and the clone
 */
public record ClonedRepo(Path directory, String commitSha) implements AutoCloseable {

	/** Deletes the directory without following symbolic links. Never throws (failures are logged). */
	@Override
	public void close() {
		FileTrees.deleteTree(directory);
	}

}
