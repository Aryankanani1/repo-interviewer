package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/** The hidden grading rubric of a question. Caps match the prompt: 2 to 4, 1 to 3 and 1 to 3 points. */
public record RubricOutput(List<String> mustMention, List<String> goodToMention, List<String> redFlags) {

	static final RubricOutput EMPTY = new RubricOutput(List.of(), List.of(), List.of());

	static final int MAX_MUST_MENTION = 4;

	static final int MAX_GOOD_TO_MENTION = 3;

	static final int MAX_RED_FLAGS = 3;

	RubricOutput normalized() {
		return new RubricOutput(Normalize.texts(mustMention, MAX_MUST_MENTION),
				Normalize.texts(goodToMention, MAX_GOOD_TO_MENTION), Normalize.texts(redFlags, MAX_RED_FLAGS));
	}

}
