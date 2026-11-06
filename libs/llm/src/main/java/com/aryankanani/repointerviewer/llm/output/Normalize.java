package com.aryankanani.repointerviewer.llm.output;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Small helpers for {@link LlmOutput#normalized()}. */
final class Normalize {

	private Normalize() {
	}

	/** Null becomes empty; surrounding whitespace is removed. */
	static String text(String value) {
		return value == null ? "" : value.strip();
	}

	/** Null becomes an empty list; items are trimmed, blank ones dropped, and at most {@code max} kept. */
	static List<String> texts(List<String> values, int max) {
		if (values == null) {
			return List.of();
		}
		return values.stream().map(Normalize::text).filter(value -> !value.isEmpty()).limit(max).toList();
	}

	/** Null becomes an empty list; null items are dropped, the rest normalized, and at most {@code max} kept. */
	static <T> List<T> list(List<T> values, int max, UnaryOperator<T> normalize) {
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(Objects::nonNull).map(normalize).limit(max).toList();
	}

	static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

}
