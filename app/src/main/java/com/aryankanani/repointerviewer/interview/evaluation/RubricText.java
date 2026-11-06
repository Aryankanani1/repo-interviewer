package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.interview.Rubric;
import java.util.List;
import java.util.stream.Collectors;

/** The rubric as readable text for the grading prompt (LLD §8.3: rubrics render as text, not JSON). */
final class RubricText {

	private RubricText() {
	}

	static String render(Rubric rubric) {
		return "Must mention:\n" + items(rubric.mustMention())
				+ "\nGood to mention:\n" + items(rubric.goodToMention())
				+ "\nRed flags:\n" + items(rubric.redFlags());
	}

	private static String items(List<String> items) {
		return items.isEmpty() ? "- none" : items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
	}

}
