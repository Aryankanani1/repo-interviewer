package com.aryankanani.repointerviewer.contracts.interview;

/**
 * Why a step after the first failed, inside a response that still carries what was saved (task 4.1): a session
 * that started but then FAILED, an answer saved but not graded, a session ended without its report. A request that
 * fails before anything is saved is a problem detail instead, like every other expected failure.
 *
 * @param code an {@code ErrorCode} name
 * @param message for the user
 * @param field the input field it is about; null when none
 */
public record ResultError(String code, String message, String field) {
}
