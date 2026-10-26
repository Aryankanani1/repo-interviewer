package com.aryankanani.repointerviewer.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One analyzed GitHub repository at one commit (table {@code project}).
 *
 * <p>Column names come from the field names (camelCase to snake_case). {@code summary} and
 * {@code repo_map} are not mapped yet (task 1.9); {@code ddl-auto=validate} only checks mapped columns.
 * No setters: status changes will go through {@code transitionTo} (task 1.9).
 */
@Entity
@Table(name = "project")
public class Project {

	/** UUIDv7, created in Java before the insert (DECISIONS.md D-014). */
	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String githubUrl;

	private String owner;

	private String repoName;

	private String commitSha;

	private String defaultBranch;

	/** Stored as text ("READY"), never as a number, so reordering the enum cannot corrupt data. */
	@Enumerated(EnumType.STRING)
	private AnalysisStatus status;

	private String failureReason;

	private String goal;

	private String domain;

	private String targetRole;

	// Repository statistics: NULL until PARSING finishes, so wrapper types, not int.
	private Integer fileCount;

	private Integer javaFileCount;

	private Integer totalLines;

	private Integer chunkCount;

	private String primaryLanguage;

	private Instant analysisStartedAt;

	private Instant analysisCompletedAt;

	/** Optimistic locking: every UPDATE checks and increments it, so two writers cannot overwrite each other. */
	@Version
	private long version;

	/** Set by Hibernate on insert. Hibernate sends every mapped column, so the DB default now() never applies. */
	@CreationTimestamp
	@Column(updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;

	/** For JPA only: Hibernate creates the object, then fills the fields. */
	protected Project() {
	}

	public UUID getId() {
		return id;
	}

	public String getGithubUrl() {
		return githubUrl;
	}

	public String getOwner() {
		return owner;
	}

	public String getRepoName() {
		return repoName;
	}

	public String getCommitSha() {
		return commitSha;
	}

	public String getDefaultBranch() {
		return defaultBranch;
	}

	public AnalysisStatus getStatus() {
		return status;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public String getGoal() {
		return goal;
	}

	public String getDomain() {
		return domain;
	}

	public String getTargetRole() {
		return targetRole;
	}

	public Integer getFileCount() {
		return fileCount;
	}

	public Integer getJavaFileCount() {
		return javaFileCount;
	}

	public Integer getTotalLines() {
		return totalLines;
	}

	public Integer getChunkCount() {
		return chunkCount;
	}

	public String getPrimaryLanguage() {
		return primaryLanguage;
	}

	public Instant getAnalysisStartedAt() {
		return analysisStartedAt;
	}

	public Instant getAnalysisCompletedAt() {
		return analysisCompletedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
