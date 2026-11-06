package com.aryankanani.repointerviewer.contracts.analysis;

/** {@link AnalysisApi#submit}: the URL, and the candidate's own context (untrusted, each optional). */
public record SubmitProjectRequest(String githubUrl, String goal, String domain, String targetRole) {
}
