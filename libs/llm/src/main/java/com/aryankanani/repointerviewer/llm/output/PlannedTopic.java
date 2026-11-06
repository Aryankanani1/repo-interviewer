package com.aryankanani.repointerviewer.llm.output;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import java.util.List;

/** One topic of a {@link PlanOutput}. */
public record PlannedTopic(String title, QuestionCategory category, List<String> targetPaths,
		List<String> targetSymbols, String rationale) {

	static final int MAX_TARGETS = 5;

	PlannedTopic normalized() {
		return new PlannedTopic(Normalize.text(title), category, Normalize.texts(targetPaths, MAX_TARGETS),
				Normalize.texts(targetSymbols, MAX_TARGETS), Normalize.text(rationale));
	}

}
