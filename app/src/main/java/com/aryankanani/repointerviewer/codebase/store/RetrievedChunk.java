package com.aryankanani.repointerviewer.codebase.store;

import java.util.UUID;

/**
 * One search result from {@link ChunkStore#search} (LLD §3.4).
 *
 * @param symbol null for text chunks
 * @param similarity cosine similarity to the query: 1 = same direction, 0 = unrelated
 */
public record RetrievedChunk(UUID id, String path, String symbol, int startLine, int endLine, String content,
		int tokenEstimate, double similarity) {

	/** Leaves out the content: it is repository text and must not end up in logs. */
	@Override
	public String toString() {
		return "RetrievedChunk[" + path + ":" + startLine + "-" + endLine + " " + symbol + " similarity="
				+ similarity + "]";
	}

}
