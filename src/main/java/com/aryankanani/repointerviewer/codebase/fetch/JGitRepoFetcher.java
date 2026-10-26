package com.aryankanani.repointerviewer.codebase.fetch;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.time.Duration;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** {@link RepoFetcher} backed by JGit (pure Java git, no git binary, no shell). */
@Component
class JGitRepoFetcher implements RepoFetcher {

	private static final Logger log = LoggerFactory.getLogger(JGitRepoFetcher.class);

	private final Duration lsRemoteTimeout;

	@Autowired
	JGitRepoFetcher(AppProperties properties) {
		this(properties.repo().lsRemoteTimeout());
	}

	JGitRepoFetcher(Duration lsRemoteTimeout) {
		this.lsRemoteTimeout = lsRemoteTimeout;
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
					.setTimeout(Math.toIntExact(Math.max(1, lsRemoteTimeout.toSeconds())))
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

	private static DomainException notFound(String message) {
		return new DomainException(ErrorCode.REPO_NOT_FOUND, message, "githubUrl");
	}

}
