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

	private static final List<String> FIXTURE_FILES = LocalDirectoryRepoFetcher.FIXTURE_FILES;

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
