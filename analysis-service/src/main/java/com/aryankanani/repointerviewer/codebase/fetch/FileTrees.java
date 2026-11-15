package com.aryankanani.repointerviewer.codebase.fetch;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-tree helpers for clones. A clone is untrusted, so neither method follows symbolic links: a link like
 * {@code docs -> /Users/me} must never make us count or delete files outside the clone.
 *
 * <p>{@link Files#walkFileTree} follows links only when asked to ({@code FOLLOW_LINKS}). Without it, a link
 * is passed to {@code visitFile} as itself, with attributes that describe the link, not its target.
 */
final class FileTrees {

	private static final Logger log = LoggerFactory.getLogger(FileTrees.class);

	private FileTrees() {
	}

	/**
	 * Deletes {@code root} and everything under it. A symbolic link is deleted, never what it points to.
	 * Does nothing if {@code root} does not exist. Logs instead of throwing: a leftover temp folder must not
	 * turn a finished analysis into a failure.
	 */
	static void deleteTree(Path root) {
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException ex) throws IOException {
					if (ex != null) {
						throw ex;
					}
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}

			});
		}
		catch (IOException ex) {
			log.warn("Could not delete {}: {}", root, ex.toString());
		}
	}

	/** Bytes in regular files under {@code root}, not counting its {@code .git} folder or symbolic links. */
	static long workingTreeBytes(Path root) throws IOException {
		Path gitDir = root.resolve(Constants.DOT_GIT);
		AtomicLong bytes = new AtomicLong();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return dir.equals(gitDir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					bytes.addAndGet(attrs.size());
				}
				return FileVisitResult.CONTINUE;
			}

		});
		return bytes.get();
	}

}
