package com.aryankanani.repointerviewer.codebase.fetch;

import java.nio.file.Path;

/**
 * Talks to the git host. Production: {@link JGitRepoFetcher}. Tests: {@code LocalDirectoryRepoFetcher}
 * (no network).
 */
public interface RepoFetcher {

	/**
	 * Reads the current HEAD commit without cloning ({@code git ls-remote}). Network call: never call it
	 * inside a database transaction.
	 *
	 * @throws com.aryankanani.repointerviewer.common.error.DomainException {@code REPO_NOT_FOUND} when the
	 *         repository is missing, private, empty, or GitHub cannot be reached
	 */
	RemoteHead resolveHead(GithubUrl url);

	/**
	 * Clones the latest commit of {@code head.defaultBranch()} (depth 1, no history, no tags) into a new
	 * directory under {@code parentDir}. Nothing from the repository is executed. Network call: never call it
	 * inside a database transaction.
	 *
	 * @param head what {@link #resolveHead} returned when the project was submitted
	 * @return the clone. Close it to delete the directory. Its {@code commitSha} differs from
	 *         {@code head.commitSha()} if the branch moved since.
	 * @throws com.aryankanani.repointerviewer.common.error.DomainException {@code REPO_TOO_LARGE} when the
	 *         files exceed {@code app.repo.max-repo-bytes} or the clone takes longer than
	 *         {@code app.repo.clone-timeout}; {@code REPO_NOT_FOUND} when the clone fails (for example, the
	 *         repository was deleted or made private). The partial directory is deleted before either.
	 */
	ClonedRepo cloneShallow(GithubUrl url, RemoteHead head, Path parentDir);

}
