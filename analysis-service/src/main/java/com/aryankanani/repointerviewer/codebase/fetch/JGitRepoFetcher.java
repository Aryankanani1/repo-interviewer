package com.aryankanani.repointerviewer.codebase.fetch;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link RepoFetcher} backed by JGit (pure Java git, no git binary, no shell).
 *
 * <p>Clones are untrusted (DECISIONS.md D-011). Nothing from the repository runs: submodules are off,
 * there is no LFS, and JGit runs no checkout hooks (a fresh clone has none anyway).
 */
@Component
class JGitRepoFetcher implements RepoFetcher {

	private static final Logger log = LoggerFactory.getLogger(JGitRepoFetcher.class);

	private final Duration lsRemoteTimeout;

	private final Duration cloneTimeout;

	private final long maxRepoBytes;

	@Autowired
	JGitRepoFetcher(AppProperties properties) {
		this(properties.repo().lsRemoteTimeout(), properties.repo().cloneTimeout(), properties.repo().maxRepoBytes());
	}

	JGitRepoFetcher(Duration lsRemoteTimeout, Duration cloneTimeout, long maxRepoBytes) {
		this.lsRemoteTimeout = lsRemoteTimeout;
		this.cloneTimeout = cloneTimeout;
		this.maxRepoBytes = maxRepoBytes;
	}

	@Override
	public RemoteHead resolveHead(GithubUrl url) {
		return resolveHead(url.cloneUri());
	}

	/** Package-private so tests can use a local repository ({@code file://...}) instead of GitHub. */
	RemoteHead resolveHead(String remoteUri) {
		Map<String, Ref> refs;
		try {
			refs = Git.lsRemoteRepository()
					.setRemote(remoteUri)
					.setTimeout(timeoutSeconds(lsRemoteTimeout))
					.callAsMap();
		}
		catch (GitAPIException | JGitInternalException ex) {
			// Missing and private repositories both fail here: GitHub asks for credentials and we have none.
			log.info("ls-remote failed for {}: {}", remoteUri, ex.getMessage());
			throw notFound("Repository not found. It must exist and be public.");
		}

		Ref head = refs.get(Constants.HEAD);
		if (head == null || head.getObjectId() == null) {
			throw notFound("The repository has no commits yet.");
		}
		return new RemoteHead(head.getObjectId().name(), defaultBranch(head, refs));
	}

	@Override
	public ClonedRepo cloneShallow(GithubUrl url, RemoteHead head, Path parentDir) {
		return cloneShallow(url.cloneUri(), head, parentDir);
	}

	/** Package-private so tests can clone a local repository ({@code file://...}) instead of GitHub. */
	ClonedRepo cloneShallow(String remoteUri, RemoteHead head, Path parentDir) {
		long started = System.nanoTime();
		Path directory = createCloneDirectory(parentDir);
		try {
			String commitSha = cloneInto(directory, remoteUri, head);
			long bytes = checkSize(directory);
			log.info("Cloned {} at {} ({} bytes) in {} ms", remoteUri, commitSha, bytes,
					Duration.ofNanos(System.nanoTime() - started).toMillis());
			return new ClonedRepo(directory, commitSha);
		}
		catch (RuntimeException ex) {
			// The caller owns the directory only once we return it, so a failed clone cleans up after itself.
			FileTrees.deleteTree(directory);
			throw ex;
		}
	}

	/** @return the commit HEAD points to after the clone */
	private String cloneInto(Path directory, String remoteUri, RemoteHead head) {
		CloneDeadline deadline = new CloneDeadline(cloneTimeout);
		CloneCommand clone = Git.cloneRepository()
				.setURI(remoteUri)
				.setDirectory(directory.toFile())
				.setDepth(1)
				.setNoTags()
				.setCloneSubmodules(false)
				.setProgressMonitor(deadline)
				// Per socket read, for a server that stops sending. The deadline covers the whole clone.
				.setTimeout(timeoutSeconds(cloneTimeout));
		if (head.defaultBranch() != null) {
			// Without setBranchesToClone, JGit fetches the tip of every branch, not only the one we analyze.
			String branchRef = Constants.R_HEADS + head.defaultBranch();
			clone.setBranch(branchRef).setBranchesToClone(List.of(branchRef));
		}

		try (Git git = clone.call()) {
			// Once cancelled, the tree may be incomplete even if call() returned, so it never counts as success.
			if (deadline.cancelled()) {
				throw timedOut();
			}
			ObjectId checkedOut = git.getRepository().resolve(Constants.HEAD);
			if (checkedOut == null) {
				throw cloneFailed("The repository has no commits yet.");
			}
			if (!checkedOut.name().equals(head.commitSha())) {
				log.info("Branch {} moved since submit: expected {}, cloned {}", head.defaultBranch(),
						head.commitSha(), checkedOut.name());
			}
			return checkedOut.name();
		}
		catch (GitAPIException | JGitInternalException ex) {
			if (deadline.cancelled()) {
				throw timedOut();
			}
			log.info("Clone failed for {}: {}", remoteUri, ex.getMessage());
			throw cloneFailed("Could not clone the repository. It must exist and be public.");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** @return the size of the checked-out files in bytes */
	private long checkSize(Path directory) {
		long bytes;
		try {
			bytes = FileTrees.workingTreeBytes(directory);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (bytes > maxRepoBytes) {
			throw new DomainException(ErrorCode.REPO_TOO_LARGE, String.format(Locale.ROOT,
					"The repository has %.1f MB of files. The limit is %.1f MB.", bytes / 1e6, maxRepoBytes / 1e6));
		}
		return bytes;
	}

	/** A new, empty directory per clone. On POSIX systems only our own user can read it (mode 700). */
	private static Path createCloneDirectory(Path parentDir) {
		try {
			Files.createDirectories(parentDir);
			return Files.createTempDirectory(parentDir, "clone-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot create a clone directory under " + parentDir, ex);
		}
	}

	/** HEAD normally points at the default branch (symref). If the server does not say so, match by commit. */
	private static String defaultBranch(Ref head, Map<String, Ref> refs) {
		if (head.isSymbolic()) {
			return Repository.shortenRefName(head.getTarget().getName());
		}
		return refs.values().stream()
				.filter(ref -> ref.getName().startsWith(Constants.R_HEADS))
				.filter(ref -> head.getObjectId().equals(ref.getObjectId()))
				.map(ref -> Repository.shortenRefName(ref.getName()))
				.findFirst()
				.orElse(null);
	}

	private static int timeoutSeconds(Duration timeout) {
		return Math.toIntExact(Math.max(1, timeout.toSeconds()));
	}

	private DomainException timedOut() {
		return new DomainException(ErrorCode.REPO_TOO_LARGE,
				"The repository could not be cloned within " + cloneTimeout.toSeconds() + " seconds.");
	}

	private static DomainException notFound(String message) {
		return new DomainException(ErrorCode.REPO_NOT_FOUND, message, "githubUrl");
	}

	/** Clone errors happen during analysis, not in a mutation, so there is no input field to point at. */
	private static DomainException cloneFailed(String message) {
		return new DomainException(ErrorCode.REPO_NOT_FOUND, message);
	}

	/**
	 * Turns {@code clone-timeout} into a deadline for the whole clone. While JGit downloads and checks out,
	 * it keeps asking its progress monitor {@code isCancelled()}; once that returns true, JGit stops.
	 */
	private static final class CloneDeadline extends EmptyProgressMonitor {

		private final long deadlineNanos;

		private volatile boolean cancelled;

		CloneDeadline(Duration timeout) {
			this.deadlineNanos = System.nanoTime() + timeout.toNanos();
		}

		@Override
		public boolean isCancelled() {
			// Subtract, then compare with 0: nanoTime values can overflow, their difference does not.
			if (System.nanoTime() - deadlineNanos >= 0) {
				cancelled = true;
			}
			return cancelled;
		}

		boolean cancelled() {
			return cancelled;
		}

	}

}
