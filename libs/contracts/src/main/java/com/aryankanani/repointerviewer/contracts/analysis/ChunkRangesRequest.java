package com.aryankanani.repointerviewer.contracts.analysis;

import java.util.List;

/** {@link AnalysisApi#chunksForRanges}: the line ranges, and at most how many tokens of code to return. */
public record ChunkRangesRequest(List<LineRange> ranges, int maxTokens) {

	public ChunkRangesRequest {
		ranges = List.copyOf(ranges);
	}

}
