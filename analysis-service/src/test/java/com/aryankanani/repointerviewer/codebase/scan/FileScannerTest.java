package com.aryankanani.repointerviewer.codebase.scan;

import static com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher.FIXTURE_FILES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.codebase.scan.ScanResult.Reason;
import com.aryankanani.repointerviewer.codebase.scan.ScanResult.Skipped;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scans a fresh copy of the fixture repository; tests add junk to the copy, never to the fixture. */
class FileScannerTest {

	private static final String SHOP = "src/main/java/com/example/shop/";

	private final FileScanner scanner = new FileScanner(1500, 10_000);

	@TempDir
	Path tempDir;

	private Path repo;

	@BeforeEach
	void copyFixture() {
		GithubUrl url = GithubUrl.parse("https://github.com/acme/shop");
		RemoteHead head = new RemoteHead(LocalDirectoryRepoFetcher.DEFAULT_SHA, "main");
		repo = new LocalDirectoryRepoFetcher().cloneShallow(url, head, tempDir.resolve("clones")).directory();
	}

	@Test
	void keepsEveryFixtureFileAndSkipsNothing() {
		ScanResult result = scanner.scan(repo);

		assertThat(paths(result)).containsExactlyElementsOf(FIXTURE_FILES);
		assertThat(result.skipped()).isEmpty();
	}

	@Test
	void skipsBuildOutputSecretsLinksAndBinariesButKeepsBuildFilesAndVendorPackages() throws IOException {
		// Folders that are never read
		write("target/classes/Shop.class", "cafebabe");
		write("target/generated-sources/Generated.java", "class Generated {}");
		write("build/libs/README.md", "# build output");
		write("node_modules/left-pad/README.md", "# left-pad");
		write(".git/config", "[core]");
		write(".idea/workspace.xml", "<project/>");
		// Secret files
		write(".env", "DB_PASSWORD=not-real");
		write("id_rsa", "not a real key");
		write("src/main/resources/server.pem", "not a real certificate");
		// Symbolic links to a file and a folder outside the clone
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Path secret = Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
		Files.createSymbolicLink(repo.resolve(SHOP + "Linked.java"), secret);
		Files.createSymbolicLink(repo.resolve("docs"), outside);
		// Not text, and too large (the limit in this test is 10 000 bytes)
		Files.write(repo.resolve(SHOP + "Blob.java"), new byte[] { 'c', 'l', 0, 's', 's' });
		write(SHOP + "Huge.java", "x".repeat(10_001));
		// Not source: ignored without being listed
		write("notes.txt", "todo");
		// Kept
		write(".github/workflows/ci.yml", "on: push");
		write("Dockerfile", "FROM eclipse-temurin:21");
		write(SHOP + "vendor/VendorService.java", "package com.example.shop.vendor;\nclass VendorService {}\n");

		ScanResult result = scanner.scan(repo);

		List<String> kept = Stream.concat(FIXTURE_FILES.stream(),
				Stream.of(".github/workflows/ci.yml", "Dockerfile", SHOP + "vendor/VendorService.java"))
				.sorted()
				.toList();
		assertThat(paths(result)).containsExactlyElementsOf(kept);
		assertThat(result.skipped()).containsExactly(
				new Skipped(".env", Reason.SECRET_FILE),
				new Skipped(".git", Reason.IGNORED_DIRECTORY),
				new Skipped(".idea", Reason.IGNORED_DIRECTORY),
				new Skipped("build", Reason.IGNORED_DIRECTORY),
				new Skipped("docs", Reason.SYMLINK),
				new Skipped("id_rsa", Reason.SECRET_FILE),
				new Skipped("node_modules", Reason.IGNORED_DIRECTORY),
				new Skipped(SHOP + "Blob.java", Reason.BINARY),
				new Skipped(SHOP + "Huge.java", Reason.TOO_LARGE),
				new Skipped(SHOP + "Linked.java", Reason.SYMLINK),
				new Skipped("src/main/resources/server.pem", Reason.SECRET_FILE),
				new Skipped("target", Reason.IGNORED_DIRECTORY));
	}

	@Test
	void recordsLanguageLinesSizeAndSha() throws IOException {
		write("guide/README.md", "hello\n");

		ScanResult result = scanner.scan(repo);

		ScannedFile readme = file(result, "guide/README.md");
		assertThat(readme.language()).isEqualTo("markdown");
		assertThat(readme.content()).isEqualTo("hello\n");
		assertThat(readme.lineCount()).isEqualTo(1);
		assertThat(readme.sizeBytes()).isEqualTo(6);
		assertThat(readme.contentSha()).isEqualTo("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03");
		assertThat(readme.toString()).doesNotContain("hello");   // content never ends up in a log line

		ScannedFile config = file(result, "src/main/resources/application.yml");
		assertThat(config.language()).isEqualTo("yaml");
		assertThat(config.lineCount()).isEqualTo(Files.readAllLines(repo.resolve(config.path())).size());
		assertThat(file(result, SHOP + "order/OrderService.java").language()).isEqualTo("java");
		assertThat(file(result, "src/main/resources/db/migration/V1__orders.sql").language()).isEqualTo("sql");
		assertThat(file(result, "pom.xml").language()).isEqualTo("xml");
	}

	@Test
	void moreFilesThanMaxFilesIsRepoTooLarge() {
		FileScanner smallLimit = new FileScanner(FIXTURE_FILES.size() - 1, 10_000);

		assertThatThrownBy(() -> smallLimit.scan(repo))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_TOO_LARGE));
	}

	@Test
	void exactlyMaxFilesIsFine() {
		FileScanner exactLimit = new FileScanner(FIXTURE_FILES.size(), 10_000);

		assertThat(exactLimit.scan(repo).files()).hasSize(FIXTURE_FILES.size());
	}

	@Test
	void repositoryWithoutJavaIsUnsupportedLanguage() throws IOException {
		Path noJava = Files.createDirectories(tempDir.resolve("no-java"));
		Files.writeString(noJava.resolve("README.md"), "# A Python project");
		Files.writeString(noJava.resolve("pom.xml"), "<project/>");
		Files.createDirectories(noJava.resolve("target"));
		Files.writeString(noJava.resolve("target/Generated.java"), "class Generated {}");   // build output: no

		assertThatThrownBy(() -> scanner.scan(noJava))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_LANGUAGE));
	}

	private void write(String path, String content) throws IOException {
		Path file = repo.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private static List<String> paths(ScanResult result) {
		return result.files().stream().map(ScannedFile::path).toList();
	}

	private static ScannedFile file(ScanResult result, String path) {
		return result.files().stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow();
	}

}
