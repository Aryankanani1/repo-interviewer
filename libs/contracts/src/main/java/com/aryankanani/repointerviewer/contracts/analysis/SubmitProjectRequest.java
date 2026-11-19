package com.aryankanani.repointerviewer.contracts.analysis;

/**
 * {@link AnalysisApi#submit}: who submits it, the URL, and the candidate's own context (untrusted, each optional).
 *
 * @param userId the JWT subject of the caller (task 4.4); the project is theirs
 */
public record SubmitProjectRequest(String userId, String githubUrl, String goal, String domain, String targetRole) {
}
