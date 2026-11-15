package com.aryankanani.repointerviewer.codebase.store;

import java.util.UUID;

/**
 * A chunk whose {@code embedding} is still NULL: what {@code EmbeddingService} needs to build its embedding text.
 *
 * @param symbol null for text chunks
 * @param content already secret-redacted, but still untrusted repository text
 */
public record PendingChunk(UUID id, String path, String symbol, String content) {

	/** Leaves out the content: it is repository text and must not end up in logs. */
	@Override
	public String toString() {
		return "PendingChunk[id=" + id + ", path=" + path + ", symbol=" + symbol + "]";
	}

}
