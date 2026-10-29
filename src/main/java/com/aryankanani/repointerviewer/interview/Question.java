package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One question (table {@code question}). The rubric and the ideal outline are stored with it but never shown
 * before the answer: the GraphQL {@code Question} type has no field for them.
 */
@Entity
@Table(name = "question")
public class Question {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID sessionId;

	private UUID topicId;

	private UUID parentQuestionId;

	/** Assigned when the question is ASKED; null while PREPARED. */
	private Integer orderIndex;

	@Enumerated(EnumType.STRING)
	private QuestionCategory category;

	private String text;

	@Column(name = "is_follow_up")
	private boolean followUp;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<CodeRef> codeRefs;

	@JdbcTypeCode(SqlTypes.JSON)
	private Rubric rubric;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> idealAnswerOutline;

	@Enumerated(EnumType.STRING)
	private QuestionStatus status;

	private String promptVersion;

	@CreationTimestamp
	@Column(updatable = false)
	private Instant createdAt;

	private Instant askedAt;

	/** For JPA only. */
	protected Question() {
	}

	/** A main question, generated but not shown yet. */
	static Question prepared(InterviewTopic topic, String text, List<CodeRef> codeRefs, Rubric rubric,
			List<String> idealAnswerOutline, String promptVersion) {
		Question question = new Question();
		question.sessionId = topic.getSessionId();
		question.topicId = topic.getId();
		question.category = topic.getCategory();
		question.text = text;
		question.followUp = false;
		question.codeRefs = List.copyOf(codeRefs);
		question.rubric = rubric;
		question.idealAnswerOutline = List.copyOf(idealAnswerOutline);
		question.status = QuestionStatus.PREPARED;
		question.promptVersion = promptVersion;
		return question;
	}

	/** A follow-up on {@code parent}: same session, topic and category. */
	static Question followUp(Question parent, String text, List<CodeRef> codeRefs, Rubric rubric,
			List<String> idealAnswerOutline, String promptVersion) {
		Question question = new Question();
		question.sessionId = parent.sessionId;
		question.topicId = parent.topicId;
		question.parentQuestionId = parent.id;
		question.category = parent.category;
		question.text = text;
		question.followUp = true;
		question.codeRefs = List.copyOf(codeRefs);
		question.rubric = rubric;
		question.idealAnswerOutline = List.copyOf(idealAnswerOutline);
		question.status = QuestionStatus.PREPARED;
		question.promptVersion = promptVersion;
		return question;
	}

	/** ASKED → ANSWERED, once its answer is graded. */
	void answered() {
		if (status == QuestionStatus.ASKED) {
			status = QuestionStatus.ANSWERED;
		}
	}

	/** ASKED → SKIPPED: the interview ended before it was answered and graded. */
	void skip() {
		if (status == QuestionStatus.ASKED) {
			status = QuestionStatus.SKIPPED;
		}
	}

	/** PREPARED → ASKED: shown to the candidate as question number {@code orderIndex}. */
	void ask(int orderIndex) {
		if (status != QuestionStatus.PREPARED) {
			throw new IllegalStateException("Question " + id + " is " + status + ", expected PREPARED");
		}
		this.status = QuestionStatus.ASKED;
		this.orderIndex = orderIndex;
		this.askedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public UUID getTopicId() {
		return topicId;
	}

	public UUID getParentQuestionId() {
		return parentQuestionId;
	}

	public Integer getOrderIndex() {
		return orderIndex;
	}

	public QuestionCategory getCategory() {
		return category;
	}

	public String getText() {
		return text;
	}

	public boolean isFollowUp() {
		return followUp;
	}

	public List<CodeRef> getCodeRefs() {
		return codeRefs;
	}

	public Rubric getRubric() {
		return rubric;
	}

	public List<String> getIdealAnswerOutline() {
		return idealAnswerOutline;
	}

	public QuestionStatus getStatus() {
		return status;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public Instant getAskedAt() {
		return askedAt;
	}

}
