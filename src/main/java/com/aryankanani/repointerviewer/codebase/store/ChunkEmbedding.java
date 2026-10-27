package com.aryankanani.repointerviewer.codebase.store;

import java.util.UUID;

/**
 * The vector for one chunk, to be written by {@link ChunkStore#saveEmbeddings}.
 *
 * <p>A record with an array component compares the array by reference in {@code equals}, not by content. Fine
 * here: this record only carries data to the database.
 */
public record ChunkEmbedding(UUID chunkId, float[] vector) {
}
