package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.codebase.fetch.ClonedRepo;
import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDirectoryRepoFetcherTest {

	/** Every file in the fixture repository. Later tasks (scanner, chunkers, retrieval) rely on these paths. */
	private static final List<String> FIXTURE_FILES = List.of(
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

	private static final GithubUrl URL = GithubUrl.parse("https://github.com/acme/shop");

	private final LocalDirectoryRepoFetcher fetcher = new LocalDirectoryRepoFetcher();

	@TempDir
	Path tempDir;

	@Test
	void cloneCopiesEveryFixtureFileIntoANewDirectory() throws IOException {
		RemoteHead head = fetcher.resolveHead(URL);

		try (ClonedRepo clone = fetcher.cloneShallow(URL, head, tempDir)) {
			assertThat(clone.directory().getParent()).isEqualTo(tempDir);
			assertThat(clone.commitSha()).isEqualTo(LocalDirectoryRepoFetcher.DEFAULT_SHA);
			assertThat(relativeFiles(clone.directory())).containsExactlyInAnyOrderElementsOf(FIXTURE_FILES);
			String service = "src/main/java/com/example/shop/order/OrderService.java";
			assertThat(clone.directory().resolve(service))
					.hasSameTextualContentAs(LocalDirectoryRepoFetcher.fixtureRoot().resolve(service));
		}
	}

	@Test
	void closingTheCloneDeletesTheCopyButNeverTheFixture() throws IOException {
		Path copy;
		try (ClonedRepo clone = fetcher.cloneShallow(URL, fetcher.resolveHead(URL), tempDir)) {
			copy = clone.directory();
		}

		assertThat(copy).doesNotExist();
		assertThat(relativeFiles(LocalDirectoryRepoFetcher.fixtureRoot()))
				.containsExactlyInAnyOrderElementsOf(FIXTURE_FILES);
	}

	@Test
	void missingRepositoryCannotBeClonedEither() {
		fetcher.markMissing("acme/shop");
		RemoteHead head = new RemoteHead(LocalDirectoryRepoFetcher.DEFAULT_SHA, "main");

		assertThatThrownBy(() -> fetcher.cloneShallow(URL, head, tempDir))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND));
	}

	private static List<String> relativeFiles(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile)
					.map(file -> root.relativize(file).toString().replace('\\', '/'))
					.toList();
		}
	}

}
