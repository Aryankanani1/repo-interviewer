package com.aryankanani.repointerviewer.codebase.chunk;

import com.aryankanani.repointerviewer.common.TokenEstimator;

/**
 * One piece of a file, ready to store in {@code code_chunk} and embed (LLD §6.6).
 *
 * <p>Line-range rule: for every type except {@link ChunkType#TYPE_HEADER}, {@code content} is exactly the
 * source lines {@code startLine..endLine}, one content line per source line. The model sees
 * {@code lines="startLine-endLine"} next to the content and cites code by those numbers, and code refs are
 * checked and turned into snippets by them, so they must be true. A TYPE_HEADER is a condensed outline; its
 * range is the whole type.
 *
 * @param symbol {@code OrderService}, {@code OrderService#refund}, {@code Order.Status}, ...; null for text
 * @param startLine 1-based, inclusive
 * @param endLine 1-based, inclusive
 * @param content already secret-redacted
 */
public record Chunk(String path, ChunkType chunkType, String symbol, int startLine, int endLine, String content,
		int tokenEstimate) {

	/** Same rule as the {@code ck_chunk_lines} check, so a bad range fails here instead of at insert time. */
	public Chunk {
		if (startLine < 1 || endLine < startLine) {
			throw new IllegalArgumentException("Invalid line range " + startLine + "-" + endLine + " in " + path);
		}
	}

	static Chunk of(String path, ChunkType chunkType, String symbol, int startLine, int endLine, String content) {
		return new Chunk(path, chunkType, symbol, startLine, endLine, content, TokenEstimator.estimate(content));
	}

	@Override
	public String toString() {
		return "Chunk[" + chunkType + " " + path + ":" + startLine + "-" + endLine + " " + symbol + "]";
	}

}
