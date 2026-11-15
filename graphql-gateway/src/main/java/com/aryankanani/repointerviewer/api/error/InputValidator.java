package com.aryankanani.repointerviewer.api.error;

import com.aryankanani.repointerviewer.common.error.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bean Validation for mutation inputs (LLD §5.2): the constraints on an input record ({@code @Size}, ...) are checked
 * before any work, and a violation becomes a payload {@link UserError} with code {@code INVALID_INPUT} and
 * {@code field = ["input", "<name>"]}.
 *
 * <p>These are generous outer limits against absurd payloads (a 1 MB "URL"). The business rules with their own
 * codes stay in the services: a 4001-character answer is still {@code ANSWER_TOO_LONG} from the service.
 */
@Component
public class InputValidator {

	private final Validator validator;

	InputValidator(Validator validator) {
		this.validator = validator;
	}

	/** The first violation, ordered by field name so the result is stable; empty when the input is valid. */
	public Optional<UserError> firstError(Object input) {
		return validator.validate(input).stream()
				.min(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
				.map(InputValidator::toUserError);
	}

	private static UserError toUserError(ConstraintViolation<Object> violation) {
		String field = violation.getPropertyPath().toString();
		return new UserError(ErrorCode.INVALID_INPUT, field + " " + violation.getMessage() + ".", List.of("input", field));
	}

}
