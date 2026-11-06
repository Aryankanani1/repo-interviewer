package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One planned topic of a session (table {@code interview_topic}): what to ask about and which code it points at.
 * Saved only after {@code TopicValidator} kept it, so its paths and symbols exist in the repository.
 */
@Entity
@Table(name = "interview_topic")
public class InterviewTopic {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID sessionId;

	private int orderIndex;

	@Enumerated(EnumType.STRING)
	private QuestionCategory category;

	private String title;

	private String rationale;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> targetPaths;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> targetSymbols;

	@Enumerated(EnumType.STRING)
	private TopicStatus status;

	private int followUpCount;

	/** For JPA only. */
	protected InterviewTopic() {
	}

	InterviewTopic(UUID sessionId, int orderIndex, QuestionCategory category, String title, String rationale,
			List<String> targetPaths, List<String> targetSymbols) {
		this.sessionId = sessionId;
		this.orderIndex = orderIndex;
		this.category = category;
		this.title = title;
		this.rationale = rationale;
		this.targetPaths = List.copyOf(targetPaths);
		this.targetSymbols = List.copyOf(targetSymbols);
		this.status = TopicStatus.PENDING;
	}

	/** PENDING → ASKED: its main question is on screen. */
	void asked() {
		requireStatus(TopicStatus.PENDING);
		status = TopicStatus.ASKED;
	}

	/** One more follow-up asked on this topic; it stays ASKED. */
	void followUpAsked() {
		requireStatus(TopicStatus.ASKED);
		followUpCount++;
	}

	/** ASKED → DONE: its main question and any follow-up are answered. */
	void done() {
		requireStatus(TopicStatus.ASKED);
		status = TopicStatus.DONE;
	}

	/** PENDING → SKIPPED: no grounded question could be written for it. */
	void skip() {
		requireStatus(TopicStatus.PENDING);
		status = TopicStatus.SKIPPED;
	}

	private void requireStatus(TopicStatus expected) {
		if (status != expected) {
			throw new IllegalStateException("Topic " + id + " is " + status + ", expected " + expected);
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public int getOrderIndex() {
		return orderIndex;
	}

	public QuestionCategory getCategory() {
		return category;
	}

	public String getTitle() {
		return title;
	}

	public String getRationale() {
		return rationale;
	}

	public List<String> getTargetPaths() {
		return targetPaths;
	}

	public List<String> getTargetSymbols() {
		return targetSymbols;
	}

	public TopicStatus getStatus() {
		return status;
	}

	public int getFollowUpCount() {
		return followUpCount;
	}

}
