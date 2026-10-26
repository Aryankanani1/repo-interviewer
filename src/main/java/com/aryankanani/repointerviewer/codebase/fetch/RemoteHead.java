package com.aryankanani.repointerviewer.codebase.fetch;

/**
 * What {@code git ls-remote} says about a repository right now.
 *
 * @param commitSha the 40-character commit HEAD points to
 * @param defaultBranch for example {@code main}; null if the server did not tell us
 */
public record RemoteHead(String commitSha, String defaultBranch) {
}
