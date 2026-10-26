package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.codebase.fetch.RepoFetcher;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test {@link RepoFetcher}: never touches the network. Every repository exists at {@link #DEFAULT_SHA}
 * unless a test says otherwise. Task 1.3 adds cloning from {@code src/test/resources/fixtures}.
 *
 * <p>It is a singleton shared by all integration tests, so tests call {@link #reset()} first.
 */
public class LocalDirectoryRepoFetcher implements RepoFetcher {

	public static final String DEFAULT_SHA = "1111111111111111111111111111111111111111";

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
		if (missing.contains(url.fullName())) {
			throw new DomainException(ErrorCode.REPO_NOT_FOUND, "Repository not found. It must exist and be public.",
					"githubUrl");
		}
		return heads.getOrDefault(url.fullName(), new RemoteHead(DEFAULT_SHA, "main"));
	}

}
