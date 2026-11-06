package com.aryankanani.repointerviewer.contracts.analysis;

import java.util.Set;

/** {@link AnalysisApi#codeNames}: every file path and every chunk symbol of one project. */
public record CodeNames(Set<String> paths, Set<String> symbols) {

	public CodeNames {
		paths = Set.copyOf(paths);
		symbols = Set.copyOf(symbols);
	}

}
