package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.project.AnalysisStatus;
import com.aryankanani.repointerviewer.project.Project;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * GraphQL type {@code Project}. Component names match schema field names, so Spring for GraphQL
 * resolves {@code githubUrl}, {@code status}, ... from the record accessors with no extra code.
 * Fields with their own resolvers ({@code files}, {@code sessions}, {@code summary}) come in later tasks.
 */
record ProjectView(
		UUID id,
		String githubUrl,
		String name,
		String commitSha,
		AnalysisStatus status,
		String failureReason,
		Context context,
		Stats stats,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	static ProjectView from(Project project) {
		return new ProjectView(
				project.getId(),
				project.getGithubUrl(),
				project.getOwner() + "/" + project.getRepoName(),
				project.getCommitSha(),
				project.getStatus(),
				project.getFailureReason(),
				new Context(project.getGoal(), project.getDomain(), project.getTargetRole()),
				Stats.from(project),
				toUtc(project.getCreatedAt()),
				toUtc(project.getUpdatedAt()));
	}

	/** The DateTime scalar serializes OffsetDateTime and ZonedDateTime, but throws on Instant. */
	private static OffsetDateTime toUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** GraphQL type {@code ProjectContext}: the candidate's own words, untrusted text. */
	record Context(String goal, String domain, String targetRole) {
	}

	/** GraphQL type {@code RepoStats}. */
	record Stats(int fileCount, int javaFileCount, int totalLines, int chunkCount, String primaryLanguage) {

		/** {@code stats} is nullable in the schema but its counts are not, so: all counts, or null. */
		static Stats from(Project project) {
			if (project.getFileCount() == null || project.getJavaFileCount() == null
					|| project.getTotalLines() == null || project.getChunkCount() == null) {
				return null;
			}
			return new Stats(project.getFileCount(), project.getJavaFileCount(), project.getTotalLines(),
					project.getChunkCount(), project.getPrimaryLanguage());
		}

	}

}
