package com.aryankanani.repointerviewer.codebase.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a line range that is longer than {@code app.chunking.max-chunk-lines} into windows of that size. Each
 * window repeats the last {@value #OVERLAP} lines of the previous one, so code near a cut is never only at the
 * edge of a chunk.
 */
final class LineWindows {

	static final int OVERLAP = 10;

	record Range(int start, int end) {
	}

	private LineWindows() {
	}

	static List<Range> split(int start, int end, int maxLines) {
		List<Range> windows = new ArrayList<>();
		int step = Math.max(1, maxLines - OVERLAP);   // a tiny max-chunk-lines must not loop forever
		for (int windowStart = start;; windowStart += step) {
			int windowEnd = Math.min(windowStart + maxLines - 1, end);
			windows.add(new Range(windowStart, windowEnd));
			if (windowEnd == end) {
				return windows;
			}
		}
	}

	/** The first window keeps the symbol; later ones get {@code #part2}, {@code #part3}, ... */
	static String partSymbol(String symbol, int index) {
		if (symbol == null || index == 0) {
			return symbol;
		}
		return symbol + "#part" + (index + 1);
	}

}
