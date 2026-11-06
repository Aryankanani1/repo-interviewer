package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/** SUMMARY: the architecture summary of a repository ({@code repo-summary.st}). */
public record SummaryOutput(String oneLiner, String architectureStyle, List<String> techStack,
		List<ModuleOutput> modules, List<String> notablePatterns, List<String> riskAreas)
		implements LlmOutput<SummaryOutput> {

	static final int MAX_MODULES = 8;

	static final int MAX_ITEMS = 10;

	@Override
	public SummaryOutput normalized() {
		return new SummaryOutput(Normalize.text(oneLiner), Normalize.text(architectureStyle),
				Normalize.texts(techStack, MAX_ITEMS), Normalize.list(modules, MAX_MODULES, ModuleOutput::normalized),
				Normalize.texts(notablePatterns, MAX_ITEMS), Normalize.texts(riskAreas, MAX_ITEMS));
	}

}
