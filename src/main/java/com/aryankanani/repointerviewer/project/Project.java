package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One analyzed GitHub repository at one commit (table {@code project}).
 *
 * <p>Column names come from the field names (camelCase to snake_case). No setters: every change is a named
 * step of the analysis ({@link #startAnalysis}, {@link #recordIndex}, {@link #complete}, {@link #fail},
 * {@link #reanalyze}), and each goes through {@link #transitionTo}, the only way to change the status.
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

	/** The arrows of the state machine in docs/LLD.md §3.2. Anything else is a bug. */
	private static final Map<AnalysisStatus, Set<AnalysisStatus>> ALLOWED = Map.of(
			AnalysisStatus.SUBMITTED, Set.of(AnalysisStatus.CLONING),
			AnalysisStatus.CLONING, Set.of(AnalysisStatus.PARSING, AnalysisStatus.FAILED),
			AnalysisStatus.PARSING, Set.of(AnalysisStatus.EMBEDDING, AnalysisStatus.FAILED),
			AnalysisStatus.EMBEDDING, Set.of(AnalysisStatus.SUMMARIZING, AnalysisStatus.FAILED),
			AnalysisStatus.SUMMARIZING, Set.of(AnalysisStatus.READY, AnalysisStatus.FAILED),
			AnalysisStatus.READY, Set.of(),
			AnalysisStatus.FAILED, Set.of(AnalysisStatus.SUBMITTED));

	// Repository statistics: NULL until PARSING finishes, so wrapper types, not int.
	private Integer fileCount;

	private Integer javaFileCount;

	private Integer totalLines;

	private Integer chunkCount;

	private String primaryLanguage;

	/** The architecture summary, a JSON document in a JSONB column. Null until READY. */
	@JdbcTypeCode(SqlTypes.JSON)
	private RepoSummary summary;

	/** The token-budgeted outline used in prompts (LLD §6.9). Null until PARSING finishes. */
	private String repoMap;

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

	/** A new project waiting for analysis. Only {@link ProjectService} creates projects. */
	Project(GithubUrl url, RemoteHead head, SubmitProjectCommand command) {
		this.githubUrl = url.normalized();
		this.owner = url.owner();
		this.repoName = url.repo();
		this.commitSha = head.commitSha();
		this.defaultBranch = head.defaultBranch();
		this.status = AnalysisStatus.SUBMITTED;
		this.goal = command.goal();
		this.domain = command.domain();
		this.targetRole = command.targetRole();
	}

	/**
	 * The only way to change the status: allowed only along an arrow of the state machine.
	 *
	 * @throws IllegalStateTransitionException for any other change
	 */
	void transitionTo(AnalysisStatus next) {
		if (!ALLOWED.get(status).contains(next)) {
			throw new IllegalStateTransitionException(id, status, next);
		}
		status = next;
	}

	/** SUBMITTED → CLONING. Clears what an earlier analysis left, so a rerun starts clean. */
	void startAnalysis() {
		transitionTo(AnalysisStatus.CLONING);
		analysisStartedAt = Instant.now();
		analysisCompletedAt = null;
		failureReason = null;
		fileCount = null;
		javaFileCount = null;
		totalLines = null;
		chunkCount = null;
		primaryLanguage = null;
		summary = null;
		repoMap = null;
	}

	/** PARSING → EMBEDDING, with what PARSING found. */
	void recordIndex(int fileCount, int javaFileCount, int totalLines, int chunkCount, String primaryLanguage,
			String repoMap) {
		transitionTo(AnalysisStatus.EMBEDDING);
		this.fileCount = fileCount;
		this.javaFileCount = javaFileCount;
		this.totalLines = totalLines;
		this.chunkCount = chunkCount;
		this.primaryLanguage = primaryLanguage;
		this.repoMap = repoMap;
	}

	/** SUMMARIZING → READY. */
	void complete(RepoSummary summary) {
		transitionTo(AnalysisStatus.READY);
		this.summary = summary;
		this.analysisCompletedAt = Instant.now();
	}

	/** Any running stage → FAILED. The reason is shown to the user, so it must be safe and readable. */
	void fail(String reason) {
		transitionTo(AnalysisStatus.FAILED);
		this.failureReason = reason;
	}

	/** FAILED → SUBMITTED ({@code reanalyzeProject}). */
	void reanalyze() {
		transitionTo(AnalysisStatus.SUBMITTED);
		this.failureReason = null;
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

	public RepoSummary getSummary() {
		return summary;
	}

	public String getRepoMap() {
		return repoMap;
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
