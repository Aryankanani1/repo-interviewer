package com.aryankanani.repointerviewer.api.error;

import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.util.List;

/**
 * GraphQL type {@code UserError}: an expected failure returned as data inside a mutation payload.
 *
 * @param field path to the input field, for example {@code ["input", "githubUrl"]}; null when not tied to one
 */
public record UserError(ErrorCode code, String message, List<String> field) {
}
