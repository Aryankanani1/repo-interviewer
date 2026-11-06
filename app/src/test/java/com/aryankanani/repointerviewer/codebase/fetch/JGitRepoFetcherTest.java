package com.aryankanani.repointerviewer.codebase.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real JGit (ls-remote and clone) against repositories created in a temp folder. No network, except
 * {@link #clonesARealPublicRepository()}, which runs only when asked for.
 */
class JGitRepoFetcherTest {

	private static final PersonIdent AUTHOR = new PersonIdent("Test", "test@example.com");

	private final JGitRepoFetcher fetcher = new JGitRepoFetcher(Duration.ofSeconds(5), Duration.ofSeconds(30),
			1_000_000);

	@TempDir
	Path tempDir;

	@Test
	void resolvesHeadCommitAndDefaultBranch() throws Exception {
		Path repoDir = tempDir.resolve("repo");
		try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("trunk").call()) {
			RevCommit commit = commitFile(git, "README.md", "hello");

			RemoteHead head = fetcher.resolveHead(uri(repoDir));

			assertThat(head.commitSha()).isEqualTo(commit.name());
			assertThat(head.defaultBranch()).isEqualTo("trunk");   // read from the repo, not assumed to be "main"
		}
	}

	@Test
	void missingRepositoryIsRepoNotFound() {
		String missing = uri(tempDir.resolve("does-not-exist"));

		assertThatThrownBy(() -> fetcher.resolveHead(missing))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND));
	}

	@Test
	void repositoryWithoutCommitsIsRepoNotFound() throws Exception {
		Path repoDir = tempDir.resolve("empty");
		try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
			assertThatThrownBy(() -> fetcher.resolveHead(uri(repoDir)))
					.isInstanceOfSatisfying(DomainException.class,
							ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND));
		}
	}

	@Test
	void shallowClonesOnlyTheLatestCommitOfTheDefaultBranch() throws Exception {
		Path repoDir = tempDir.resolve("repo");
		Path parentDir = tempDir.resolve("clones");
		try (Git source = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("trunk").call()) {
			commitFile(source, "README.md", "first");
			source.branchCreate().setName("feature").call();   // another branch, left at the first commit
			RevCommit latest = commitFile(source, "README.md", "second");
			source.tag().setName("v1").setAnnotated(false).call();

			Path cloneDir;
			try (ClonedRepo clone = fetcher.cloneShallow(uri(repoDir), new RemoteHead(latest.name(), "trunk"),
					parentDir); Git cloned = Git.open(clone.directory().toFile())) {
				cloneDir = clone.directory();

				assertThat(clone.commitSha()).isEqualTo(latest.name());
				assertThat(cloneDir.getParent()).isEqualTo(parentDir);
				assertThat(cloneDir.resolve("README.md")).hasContent("second");
				assertThat(cloned.log().call()).hasSize(1);   // depth 1: no history
				assertThat(remoteBranches(cloned)).containsExactly("refs/remotes/origin/trunk");   // not "feature"
				assertThat(cloned.tagList().call()).isEmpty();
			}
			assertThat(cloneDir).doesNotExist();   // close() deleted the clone
		}
	}

	@Test
	void branchThatMovedSinceSubmitReturnsTheCommitActuallyCloned() throws Exception {
		Path repoDir = tempDir.resolve("repo");
		try (Git source = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("trunk").call()) {
			RevCommit submitted = commitFile(source, "README.md", "first");
			RevCommit pushedLater = commitFile(source, "README.md", "second");

			try (ClonedRepo clone = fetcher.cloneShallow(uri(repoDir), new RemoteHead(submitted.name(), "trunk"),
					tempDir.resolve("clones"))) {
				assertThat(clone.commitSha()).isEqualTo(pushedLater.name());
			}
		}
	}

	@Test
	void repositoryOverMaxRepoBytesIsRepoTooLargeAndLeavesNothingBehind() throws Exception {
		JGitRepoFetcher smallLimit = new JGitRepoFetcher(Duration.ofSeconds(5), Duration.ofSeconds(30), 1_000);
		Path repoDir = tempDir.resolve("repo");
		Path parentDir = tempDir.resolve("clones");
		try (Git source = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("trunk").call()) {
			RevCommit commit = commitFile(source, "data.txt", "x".repeat(5_000));

			assertThatThrownBy(() -> smallLimit.cloneShallow(uri(repoDir), new RemoteHead(commit.name(), "trunk"),
					parentDir))
					.isInstanceOfSatisfying(DomainException.class,
							ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_TOO_LARGE));
			assertThat(parentDir).isEmptyDirectory();
		}
	}

	@Test
	void cloneThatMissesTheDeadlineIsRepoTooLargeAndLeavesNothingBehind() throws Exception {
		JGitRepoFetcher noTime = new JGitRepoFetcher(Duration.ofSeconds(5), Duration.ofNanos(1), 1_000_000);
		Path repoDir = tempDir.resolve("repo");
		Path parentDir = tempDir.resolve("clones");
		try (Git source = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("trunk").call()) {
			RevCommit commit = commitFile(source, "README.md", "hello");

			// The size limit is far away, so REPO_TOO_LARGE can only come from the deadline here.
			assertThatThrownBy(() -> noTime.cloneShallow(uri(repoDir), new RemoteHead(commit.name(), "trunk"),
					parentDir))
					.isInstanceOfSatisfying(DomainException.class,
							ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_TOO_LARGE));
			assertThat(parentDir).isEmptyDirectory();
		}
	}

	@Test
	void failedCloneIsRepoNotFoundAndLeavesNothingBehind() {
		Path parentDir = tempDir.resolve("clones");
		String missing = uri(tempDir.resolve("deleted-after-submit"));

		assertThatThrownBy(() -> fetcher.cloneShallow(missing, new RemoteHead("0".repeat(40), "main"), parentDir))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND));
		assertThat(parentDir).isEmptyDirectory();
	}

	/**
	 * The one manual check against GitHub (task 1.3 acceptance). Skipped unless you pass a URL:
	 * {@code ./mvnw test -Dtest=JGitRepoFetcherTest -Dmanual.clone.url=https://github.com/owner/repo}
	 */
	@Test
	@EnabledIfSystemProperty(named = "manual.clone.url", matches = "https://github\\.com/.+")
	void clonesARealPublicRepository() throws IOException {
		GithubUrl url = GithubUrl.parse(System.getProperty("manual.clone.url"));
		// Same limits as application.yml.
		JGitRepoFetcher real = new JGitRepoFetcher(Duration.ofSeconds(15), Duration.ofSeconds(60), 25_000_000);

		RemoteHead head = real.resolveHead(url);
		long started = System.nanoTime();
		Path cloneDir;
		try (ClonedRepo clone = real.cloneShallow(url, head, tempDir)) {
			long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
			cloneDir = clone.directory();

			assertThat(clone.commitSha()).isEqualTo(head.commitSha());
			assertThat(cloneDir).isNotEmptyDirectory();
			System.out.printf("MANUAL CLONE %s branch=%s commit=%s size=%d bytes in %d ms%n", url.fullName(),
					head.defaultBranch(), clone.commitSha(), FileTrees.workingTreeBytes(cloneDir), millis);
		}
		assertThat(cloneDir).doesNotExist();
	}

	private static RevCommit commitFile(Git git, String name, String content) throws Exception {
		Files.writeString(git.getRepository().getWorkTree().toPath().resolve(name), content);
		git.add().addFilepattern(name).call();
		// setSign(false): a developer's global gitconfig may ask for signed commits, which would need a key.
		return git.commit().setMessage("change " + name).setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
	}

	private static List<String> remoteBranches(Git git) throws IOException {
		return git.getRepository().getRefDatabase().getRefsByPrefix(Constants.R_REMOTES).stream()
				.map(Ref::getName)
				.toList();
	}

	private static String uri(Path repoDir) {
		return repoDir.toUri().toString();
	}

}
