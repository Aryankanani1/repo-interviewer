package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.codebase.fetch.ClonedRepo;
import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.codebase.fetch.RepoFetcher;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test {@link RepoFetcher}: never touches the network. Every repository exists at {@link #DEFAULT_SHA}
 * unless a test says otherwise, and "cloning" any of them copies the fixture repository
 * {@code src/test/resources/fixtures/sample-repo} into a new directory.
 *
 * <p>The fixture is read from the test classpath ({@code target/test-classes}). Maven does not copy dotfiles
 * such as {@code .gitignore} there, so tests that need one create it in the copy.
 *
 * <p>It is a singleton shared by all integration tests, so tests call {@link #reset()} first.
 */
public class LocalDirectoryRepoFetcher implements RepoFetcher {

	public static final String DEFAULT_SHA = "1111111111111111111111111111111111111111";

	public static final String FIXTURE = "/fixtures/sample-repo";

	/** Every file in the fixture repository, sorted. Tests of the scanner, chunkers and retrieval rely on it. */
	public static final List<String> FIXTURE_FILES = List.of(
			"README.md",
			"pom.xml",
			"src/main/java/com/example/shop/ShopApplication.java",
			"src/main/java/com/example/shop/order/Order.java",
			"src/main/java/com/example/shop/order/OrderController.java",
			"src/main/java/com/example/shop/order/OrderRepository.java",
			"src/main/java/com/example/shop/order/OrderService.java",
			"src/main/java/com/example/shop/order/RefundRequest.java",
			"src/main/resources/application.yml",
			"src/main/resources/db/migration/V1__orders.sql",
			"src/test/java/com/example/shop/order/OrderServiceTest.java");

	private final Map<String, RemoteHead> heads = new ConcurrentHashMap<>();

	private final Set<String> missing = ConcurrentHashMap.newKeySet();

	public void reset() {
		heads.clear();
		missing.clear();
	}

	/** @param fullName for example {@code acme/shop} */
	public void setHead(String fullName, String commitSha) {
		heads.put(fullName, new RemoteHead(commitSha, "main"));
	}

	public void markMissing(String fullName) {
		missing.add(fullName);
	}

	@Override
	public RemoteHead resolveHead(GithubUrl url) {
		failIfMissing(url);
		return heads.getOrDefault(url.fullName(), new RemoteHead(DEFAULT_SHA, "main"));
	}

	@Override
	public ClonedRepo cloneShallow(GithubUrl url, RemoteHead head, Path parentDir) {
		failIfMissing(url);
		try {
			Files.createDirectories(parentDir);
			Path directory = Files.createTempDirectory(parentDir, "clone-");
			copyTree(fixtureRoot(), directory);
			return new ClonedRepo(directory, head.commitSha());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The fixture on disk. Tests may read it but must never change it. */
	public static Path fixtureRoot() {
		URL url = LocalDirectoryRepoFetcher.class.getResource(FIXTURE);
		if (url == null) {
			throw new IllegalStateException(FIXTURE + " is not on the test classpath");
		}
		try {
			return Path.of(url.toURI());
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void failIfMissing(GithubUrl url) {
		if (missing.contains(url.fullName())) {
			throw new DomainException(ErrorCode.REPO_NOT_FOUND, "Repository not found. It must exist and be public.",
					"githubUrl");
		}
	}

	private static void copyTree(Path source, Path target) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(target.resolve(source.relativize(dir)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, target.resolve(source.relativize(file)));
				return FileVisitResult.CONTINUE;
			}

		});
	}

}
