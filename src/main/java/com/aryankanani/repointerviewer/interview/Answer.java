package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.interview.evaluation.Evaluation;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * The candidate's answer to one question (table {@code answer}), and its grade once evaluated. Saved before the
 * model grades it, so a failed evaluation never loses the answer: sending the same text again finishes the job.
 * The text is untrusted and never logged.
 */
@Entity
@Table(name = "answer")
public class Answer {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID questionId;

	private String text;

	/** SHA-256 of the text: how a re-sent answer is recognized (LLD §9.2). */
	private String textSha;

	/** {@code SMALLINT} in the table, so {@code Short} here. Null until evaluated. */
	private Short score;

	@Enumerated(EnumType.STRING)
	private AnswerVerdict verdict;

	private String feedback;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> coveredPoints;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> missedPoints;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> incorrectClaims;

	private Boolean followUpNeeded;

	private String followUpReason;

	private Instant submittedAt;

	private Instant evaluatedAt;

	/** For JPA only. */
	protected Answer() {
	}

	Answer(UUID questionId, String text, String textSha) {
		this.questionId = questionId;
		this.text = text;
		this.textSha = textSha;
		this.submittedAt = Instant.now();
	}

	boolean isEvaluated() {
		return evaluatedAt != null;
	}

	/** Stores the grade. Done at most once: the caller holds a row lock and checks {@link #isEvaluated} first. */
	void evaluate(Evaluation evaluation) {
		if (isEvaluated()) {
			throw new IllegalStateException("Answer " + id + " is already evaluated");
		}
		this.score = (short) evaluation.score();
		this.verdict = evaluation.verdict();
		this.feedback = evaluation.feedback();
		this.coveredPoints = List.copyOf(evaluation.coveredPoints());
		this.missedPoints = List.copyOf(evaluation.missedPoints());
		this.incorrectClaims = List.copyOf(evaluation.incorrectClaims());
		this.followUpNeeded = evaluation.followUpNeeded();
		this.followUpReason = evaluation.followUpReason();
		this.evaluatedAt = Instant.now();
	}

	/** The stored grade, for deciding the next step on a resumed request. Null until evaluated. */
	Evaluation evaluation() {
		if (!isEvaluated()) {
			return null;
		}
		return new Evaluation(score, verdict, feedback, coveredPoints, missedPoints, incorrectClaims,
				Boolean.TRUE.equals(followUpNeeded), followUpReason);
	}

	public UUID getId() {
		return id;
	}

	public UUID getQuestionId() {
		return questionId;
	}

	public String getText() {
		return text;
	}

	public String getTextSha() {
		return textSha;
	}

	public Integer getScore() {
		return score == null ? null : score.intValue();
	}

	public AnswerVerdict getVerdict() {
		return verdict;
	}

	public String getFeedback() {
		return feedback;
	}

	public List<String> getCoveredPoints() {
		return coveredPoints;
	}

	public List<String> getMissedPoints() {
		return missedPoints;
	}

	public List<String> getIncorrectClaims() {
		return incorrectClaims;
	}

	public Instant getSubmittedAt() {
		return submittedAt;
	}

	public Instant getEvaluatedAt() {
		return evaluatedAt;
	}

	/** Leaves out the text: it is the candidate's untrusted answer. */
	@Override
	public String toString() {
		return "Answer[id=" + id + ", questionId=" + questionId + ", score=" + score + "]";
	}

}
