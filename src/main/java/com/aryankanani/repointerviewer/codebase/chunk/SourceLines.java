package com.aryankanani.repointerviewer.codebase.chunk;

import java.util.List;

/**
 * A file's lines, numbered from 1 like an editor and like JavaParser. {@link String#lines()} splits on
 * {@code \n}, {@code \r\n} and {@code \r}, the same line breaks JavaParser counts, so both agree on numbers.
 */
final class SourceLines {

	private final List<String> lines;

	SourceLines(String content) {
		this.lines = content.lines().toList();
	}

	int count() {
		return lines.size();
	}

	/** @param number 1-based */
	String line(int number) {
		return lines.get(number - 1);
	}

	boolean isBlank(int number) {
		return line(number).isBlank();
	}

	/** Lines {@code start..end} (inclusive), joined with {@code \n}. */
	String slice(int start, int end) {
		return String.join("\n", lines.subList(start - 1, end));
	}

}
