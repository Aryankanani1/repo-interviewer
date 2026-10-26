package com.aryankanani.repointerviewer.codebase.scan;

import com.aryankanani.repointerviewer.codebase.scan.ScanResult.Reason;
import com.aryankanani.repointerviewer.codebase.scan.ScanResult.Skipped;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Walks a cloned repository and reads the files worth analyzing (LLD §6.5). The rules for each path are in
 * {@link FilePatterns}; this class applies them in order and enforces the limits.
 *
 * <p>Clones are untrusted. The walk never follows symbolic links ({@code walkFileTree} without
 * {@code FOLLOW_LINKS} hands a link to {@code visitFile} as itself), links are listed as {@code SYMLINK}, and
 * files are opened with {@code NOFOLLOW_LINKS} as a second guard.
 */
@Component
public class FileScanner {

	private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

	private static final int BINARY_SNIFF_BYTES = 8 * 1024;

	private final int maxFiles;

	private final long maxFileBytes;

	@Autowired
	FileScanner(AppProperties properties) {
		this(properties.repo().maxFiles(), properties.repo().maxFileBytes());
	}

	FileScanner(int maxFiles, long maxFileBytes) {
		this.maxFiles = maxFiles;
		this.maxFileBytes = maxFileBytes;
	}

	/**
	 * @param root the working tree of a clone
	 * @throws DomainException {@code REPO_TOO_LARGE} when more than {@code app.repo.max-files} files would be
	 *         kept; {@code UNSUPPORTED_LANGUAGE} when there is no {@code .java} file
	 */
	public ScanResult scan(Path root) {
		Walker walker = new Walker(root);
		try {
			Files.walkFileTree(root, walker);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (walker.files.stream().noneMatch(file -> file.language().equals(FilePatterns.JAVA))) {
			throw new DomainException(ErrorCode.UNSUPPORTED_LANGUAGE,
					"No Java source files found. Only Java repositories are supported.");
		}

		List<ScannedFile> files = walker.files.stream().sorted(Comparator.comparing(ScannedFile::path)).toList();
		List<Skipped> skipped = walker.skipped.stream().sorted(Comparator.comparing(Skipped::path)).toList();
		log.info("Scan kept {} files, skipped {} {}, ignored {} other files", files.size(), skipped.size(),
				countByReason(skipped), walker.ignored);
		return new ScanResult(files, skipped);
	}

	private static Map<Reason, Long> countByReason(List<Skipped> skipped) {
		Map<Reason, Long> counts = new TreeMap<>();
		skipped.forEach(entry -> counts.merge(entry.reason(), 1L, Long::sum));
		return counts;
	}

	private static boolean isBinary(byte[] bytes) {
		int end = Math.min(bytes.length, BINARY_SNIFF_BYTES);
		for (int i = 0; i < end; i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java runtime must support SHA-256", ex);
		}
	}

	/** One walk. Not thread-safe; {@link #scan} creates a new one each time. */
	private final class Walker extends SimpleFileVisitor<Path> {

		private final Path root;

		private final List<ScannedFile> files = new ArrayList<>();

		private final List<Skipped> skipped = new ArrayList<>();

		private int ignored;

		Walker(Path root) {
			this.root = root;
		}

		@Override
		public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
			if (!dir.equals(root) && FilePatterns.isIgnoredDirectory(relative(dir))) {
				skipped.add(new Skipped(relative(dir), Reason.IGNORED_DIRECTORY));
				return FileVisitResult.SKIP_SUBTREE;
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
			String path = relative(file);
			if (attrs.isSymbolicLink()) {
				skipped.add(new Skipped(path, Reason.SYMLINK));
			}
			else if (!attrs.isRegularFile()) {
				ignored++;
			}
			else if (FilePatterns.isSecretFile(path)) {
				skipped.add(new Skipped(path, Reason.SECRET_FILE));
			}
			else if (!FilePatterns.isIncluded(path)) {
				ignored++;
			}
			else if (attrs.size() > maxFileBytes) {
				skipped.add(new Skipped(path, Reason.TOO_LARGE));
			}
			else {
				read(file, path);
			}
			return FileVisitResult.CONTINUE;
		}

		private void read(Path file, String path) throws IOException {
			byte[] bytes;
			try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
				bytes = in.readAllBytes();
			}
			if (isBinary(bytes)) {
				skipped.add(new Skipped(path, Reason.BINARY));
				return;
			}
			if (files.size() >= maxFiles) {
				throw new DomainException(ErrorCode.REPO_TOO_LARGE,
						"The repository has more than " + maxFiles + " source files.");
			}
			String content = new String(bytes, StandardCharsets.UTF_8);   // invalid UTF-8 becomes U+FFFD
			files.add(new ScannedFile(path, FilePatterns.language(path), content, (int) content.lines().count(),
					bytes.length, sha256(bytes)));
		}

		/** Repo-relative with forward slashes, whatever the operating system. */
		private String relative(Path path) {
			Path relative = root.relativize(path);
			return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
		}

	}

}
