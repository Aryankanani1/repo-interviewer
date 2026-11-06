package com.aryankanani.repointerviewer.contracts.analysis;

/** {@link AnalysisApi#searchChunks}: what to look for, and at most how many tokens of code to return. */
public record ChunkSearchRequest(TopicQuery query, int maxTokens) {
}
