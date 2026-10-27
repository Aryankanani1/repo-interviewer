package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One interview about one project (table {@code interview_session}). Linked to its project, topics and questions
 * by plain ids, not JPA relationships: nothing is loaded lazily behind your back, and the GraphQL layer loads
 * related rows explicitly. {@code overall_score} and {@code report} arrive in task 1.12.
 */
@Entity
@Table(name = "interview_session")
public class InterviewSession {

	/** The session state machine (LLD §3.2). */
	private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED = Map.of(
			SessionStatus.PLANNING, Set.of(SessionStatus.IN_PROGRESS, SessionStatus.FAILED),
			SessionStatus.IN_PROGRESS, Set.of(SessionStatus.COMPLETED, SessionStatus.ABANDONED),
			SessionStatus.COMPLETED, Set.of(),
			SessionStatus.ABANDONED, Set.of(),
			SessionStatus.FAILED, Set.of());

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID projectId;

	@Enumerated(EnumType.STRING)
	private SessionStatus status;

	@Enumerated(EnumType.STRING)
	private Difficulty difficulty;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<QuestionCategory> focusAreas;

	private int plannedQuestionCount;

	private UUID currentQuestionId;

	private String failureReason;

	/**
	 * Read-only here. {@code LlmBudget} counts calls with an atomic UPDATE; if Hibernate wrote this column, a
	 * session loaded before a model call and saved after it would put the old count back.
	 */
	@Column(insertable = false, updatable = false)
	private int llmCallCount;

	/** Optimistic locking: two requests changing the same session cannot both win. */
	@Version
	private long version;

	private Instant startedAt;

	private Instant completedAt;

	@CreationTimestamp
	@Column(updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	private Instant updatedAt;

	/** For JPA only. */
	protected InterviewSession() {
	}

	InterviewSession(UUID projectId, Difficulty difficulty, List<QuestionCategory> focusAreas, int requestedCount) {
		this.projectId = projectId;
		this.status = SessionStatus.PLANNING;
		this.difficulty = difficulty;
		this.focusAreas = List.copyOf(focusAreas);
		this.plannedQuestionCount = requestedCount;
		this.startedAt = Instant.now();
	}

	void transitionTo(SessionStatus next) {
		if (!ALLOWED.get(status).contains(next)) {
			throw new IllegalStateException("Session " + id + " cannot go from " + status + " to " + next);
		}
		status = next;
	}

	/** The plan may keep fewer topics than requested: the count is what the interview will really have. */
	void planned(int topicCount) {
		this.plannedQuestionCount = topicCount;
	}

	/** PLANNING → IN_PROGRESS with the first question shown. */
	void begin(UUID firstQuestionId) {
		transitionTo(SessionStatus.IN_PROGRESS);
		this.currentQuestionId = firstQuestionId;
	}

	/** PLANNING → FAILED. The reason is shown to the user. */
	void fail(String reason) {
		transitionTo(SessionStatus.FAILED);
		this.failureReason = reason;
		this.completedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public SessionStatus getStatus() {
		return status;
	}

	public Difficulty getDifficulty() {
		return difficulty;
	}

	public List<QuestionCategory> getFocusAreas() {
		return focusAreas;
	}

	public int getPlannedQuestionCount() {
		return plannedQuestionCount;
	}

	public UUID getCurrentQuestionId() {
		return currentQuestionId;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public int getLlmCallCount() {
		return llmCallCount;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

}
