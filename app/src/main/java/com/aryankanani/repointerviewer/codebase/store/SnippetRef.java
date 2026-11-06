package com.aryankanani.repointerviewer.codebase.store;

import java.util.UUID;

/** Lines of one file of one project whose text is wanted ({@link ChunkStore#snippets}). 1-based, inclusive. */
public record SnippetRef(UUID projectId, String path, int startLine, int endLine) {
}
