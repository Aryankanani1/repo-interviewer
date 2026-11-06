package com.aryankanani.repointerviewer.common.error;

import java.util.Optional;

/**
 * An expected failure (bad input, missing repo, wrong state). The api module returns it to the
 * client as a {@code UserError} in the mutation payload, so the message must be safe to show:
 * no stack traces, internal ids or prompt text.
 */
public class DomainException extends RuntimeException {

	private final ErrorCode code;

	private final String field;

	public DomainException(ErrorCode code, String message) {
		this(code, message, null);
	}

	/** @param field the input field that caused the error, for example {@code "githubUrl"}; may be null */
	public DomainException(ErrorCode code, String message, String field) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public ErrorCode code() {
		return code;
	}

	public Optional<String> field() {
		return Optional.ofNullable(field);
	}

}
