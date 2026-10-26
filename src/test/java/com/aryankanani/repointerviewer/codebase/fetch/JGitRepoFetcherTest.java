package com.aryankanani.repointerviewer.codebase.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs real JGit ls-remote against repositories created in a temp folder. No network. */
class JGitRepoFetcherTest {

	private final JGitRepoFetcher fetcher = new JGitRepoFetcher(Duration.ofSeconds(5));

	@TempDir
	Path tempDir;

	@Test
	void resolvesHeadCommitAndDefaultBranch() throws Exception {
		Path repoDir = tempDir.resolve("repo");
		try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("trunk").call()) {
			Files.writeString(repoDir.resolve("README.md"), "hello");
			git.add().addFilepattern("README.md").call();
			PersonIdent author = new PersonIdent("Test", "test@example.com");
			RevCommit commit = git.commit().setMessage("init").setAuthor(author).setCommitter(author)
					.setSign(false).call();

			RemoteHead head = fetcher.resolveHead(repoDir.toUri().toString());

			assertThat(head.commitSha()).isEqualTo(commit.name());
			assertThat(head.defaultBranch()).isEqualTo("trunk");   // read from the repo, not assumed to be "main"
		}
	}

	@Test
	void missingRepositoryIsRepoNotFound() {
		String missing = tempDir.resolve("does-not-exist").toUri().toString();

		assertThatThrownBy(() -> fetcher.resolveHead(missing))
				.isInstanceOfSatisfying(DomainException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND));
	}

	@Test
	void repositoryWithoutCommitsIsRepoNotFound() throws Exception {
		Path repoDir = tempDir.resolve("empty");
		try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
			assertThatThrownBy(() -> fetcher.resolveHead(repoDir.toUri().toString()))
					.isInstanceOfSatisfying(DomainException.class,
							ex -> assertThat(ex.code()).isEqualTo(ErrorCode.REPO_NOT_FOUND));
		}
	}

}
