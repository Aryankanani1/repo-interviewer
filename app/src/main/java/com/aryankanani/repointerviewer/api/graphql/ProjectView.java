package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.RepoSummary;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * GraphQL type {@code Project}. Component names match schema field names, so Spring for GraphQL
 * resolves {@code githubUrl}, {@code status}, ... from the record accessors with no extra code.
 * {@code summary} is the stored {@link RepoSummary} record and {@code stats} the analysis side's
 * {@link ProjectDto.Stats}; their field names match the GraphQL types ({@code RepoSummary}, {@code RepoStats}).
 * Fields with their own resolvers ({@code files}, {@code sessions}) come in later tasks.
 */
record ProjectView(
		UUID id,
		String githubUrl,
		String name,
		String commitSha,
		AnalysisStatus status,
		String failureReason,
		Context context,
		ProjectDto.Stats stats,
		RepoSummary summary,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	static ProjectView from(ProjectDto project) {
		return new ProjectView(
				project.id(),
				project.githubUrl(),
				project.owner() + "/" + project.repoName(),
				project.commitSha(),
				project.status(),
				project.failureReason(),
				new Context(project.goal(), project.domain(), project.targetRole()),
				project.stats(),
				project.summary(),
				toUtc(project.createdAt()),
				toUtc(project.updatedAt()));
	}

	/** The DateTime scalar serializes OffsetDateTime and ZonedDateTime, but throws on Instant. */
	private static OffsetDateTime toUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** GraphQL type {@code ProjectContext}: the candidate's own words, untrusted text. */
	record Context(String goal, String domain, String targetRole) {
	}

}
