package com.aryankanani.repointerviewer.codebase.fetch;

/**
 * Talks to the git host. Production: {@link JGitRepoFetcher}. Tests: {@code LocalDirectoryRepoFetcher}
 * (no network). Task 1.3 adds {@code cloneShallow}.
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

}
