package com.aryankanani.repointerviewer.contracts.analysis;

import java.util.List;

/** One page of {@link AnalysisApi#page}. {@code hasNext}: there is at least one more project after the last one. */
public record ProjectPage(List<ProjectDto> items, boolean hasNext) {

	public ProjectPage {
		items = List.copyOf(items);
	}

}
