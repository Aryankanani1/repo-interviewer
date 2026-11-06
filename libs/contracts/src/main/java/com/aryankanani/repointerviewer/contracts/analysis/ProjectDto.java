package com.aryankanani.repointerviewer.contracts.analysis;

import java.time.Instant;
import java.util.UUID;

/**
 * A project as the analysis side shows it to others: no repo map or files (see {@link ProjectContext} and
 * {@link AnalysisApi#codeNames}).
 *
 * @param goal the candidate's own words, untrusted, like {@code domain} and {@code targetRole}
 * @param stats null until the analysis has counted everything
 * @param summary null until the SUMMARIZING stage is done
 */
public record ProjectDto(UUID id, String githubUrl, String owner, String repoName, String commitSha,
		AnalysisStatus status, String failureReason, String goal, String domain, String targetRole, Stats stats,
		RepoSummary summary, Instant createdAt, Instant updatedAt) {

	public record Stats(int fileCount, int javaFileCount, int totalLines, int chunkCount, String primaryLanguage) {
	}

	/** Leaves out the candidate's texts and the summary, like {@link ProjectContext#toString()}. */
	@Override
	public String toString() {
		return "ProjectDto[id=" + id + ", status=" + status + ", name=" + owner + "/" + repoName + "]";
	}

}
