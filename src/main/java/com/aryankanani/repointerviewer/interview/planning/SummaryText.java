package com.aryankanani.repointerviewer.interview.planning;

import com.aryankanani.repointerviewer.project.RepoSummary;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The stored summary as readable text for the plan prompt (LLD §8.3: summaries render as text, not JSON). Text is
 * what the model reads best, and it costs fewer tokens than JSON with its quotes and braces.
 */
final class SummaryText {

	private SummaryText() {
	}

	/** @return null when there is no summary, which the prompt shows as "(not provided)" */
	static String render(RepoSummary summary) {
		if (summary == null) {
			return null;
		}
		String modules = summary.modules().stream()
				.map(module -> "- " + module.name() + ": " + module.responsibility()
						+ (module.keyClasses().isEmpty() ? "" : " (key classes: " + String.join(", ", module.keyClasses()) + ")"))
				.collect(Collectors.joining("\n"));
		return "One-liner: " + summary.oneLiner()
				+ "\nArchitecture: " + summary.architectureStyle()
				+ "\nTech stack: " + (summary.techStack().isEmpty() ? "none listed" : String.join(", ", summary.techStack()))
				+ "\nModules:\n" + (modules.isEmpty() ? "none listed" : modules)
				+ "\nNotable patterns:\n" + items(summary.notablePatterns())
				+ "\nRisk areas:\n" + items(summary.riskAreas());
	}

	private static String items(List<String> items) {
		return items.isEmpty() ? "none listed" : items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
	}

}
