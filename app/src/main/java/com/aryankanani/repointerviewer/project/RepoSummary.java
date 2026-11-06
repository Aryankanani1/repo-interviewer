package com.aryankanani.repointerviewer.project;

import java.util.List;

/**
 * The architecture summary of a project, stored as JSON in {@code project.summary} (LLD §3.4). Built from the
 * model's output by {@link SummaryValidator}, so every class it names exists in the repository. Field names
 * match the GraphQL type {@code RepoSummary}.
 */
public record RepoSummary(String oneLiner, String architectureStyle, List<String> techStack,
		List<ModuleSummary> modules, List<String> notablePatterns, List<String> riskAreas) {

	/** GraphQL type {@code ModuleSummary}. */
	public record ModuleSummary(String name, String responsibility, List<String> keyClasses) {
	}

}
