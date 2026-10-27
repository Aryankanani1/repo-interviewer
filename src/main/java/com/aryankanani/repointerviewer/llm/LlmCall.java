package com.aryankanani.repointerviewer.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One attempt at a model call (table {@code llm_call}), written whether it worked or not. What it cost (tokens),
 * how long it took, and which prompt version produced it: the numbers in RESULTS.md come from here.
 * Append-only: never updated after the insert.
 */
@Entity
@Table(name = "llm_call")
public class LlmCall {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Enumerated(EnumType.STRING)
	private LlmPurpose purpose;

	private String model;

	private String promptVersion;

	private UUID projectId;

	private UUID sessionId;

	private Integer inputTokens;

	private Integer outputTokens;

	private Integer cachedTokens;

	private int latencyMs;

	private boolean success;

	/** Null on success. {@code OUTPUT_INVALID}, or the class name of the API error. */
	private String errorType;

	private int attempt;

	@CreationTimestamp
	@Column(updatable = false)
	private Instant createdAt;

	/** For JPA only. */
	protected LlmCall() {
	}

	LlmCall(LlmRequest<?> request, String model, String promptVersion, int attempt, int latencyMs, Tokens tokens,
			String errorType) {
		this.purpose = request.purpose();
		this.model = model;
		this.promptVersion = promptVersion;
		this.projectId = request.projectId();
		this.sessionId = request.sessionId();
		this.inputTokens = tokens.input();
		this.outputTokens = tokens.output();
		this.cachedTokens = tokens.cached();
		this.latencyMs = latencyMs;
		this.success = errorType == null;
		this.errorType = errorType;
		this.attempt = attempt;
	}

	/** Token counts from the response's usage metadata; null when there was no response. */
	record Tokens(Integer input, Integer output, Integer cached) {

		static final Tokens NONE = new Tokens(null, null, null);

	}

	public UUID getId() {
		return id;
	}

	public LlmPurpose getPurpose() {
		return purpose;
	}

	public String getModel() {
		return model;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public Integer getInputTokens() {
		return inputTokens;
	}

	public Integer getOutputTokens() {
		return outputTokens;
	}

	public Integer getCachedTokens() {
		return cachedTokens;
	}

	public int getLatencyMs() {
		return latencyMs;
	}

	public boolean isSuccess() {
		return success;
	}

	public String getErrorType() {
		return errorType;
	}

	public int getAttempt() {
		return attempt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
