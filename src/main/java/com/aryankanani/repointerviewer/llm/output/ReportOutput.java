package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/**
 * REPORT: the end-of-interview text ({@code session-report.st}). Scores are not here: code computes them
 * (LLD §9.5) and the model only writes about them. Caps match the prompt: 2 to 4 and 3 to 5 items.
 */
public record ReportOutput(String summary, List<String> strengths, List<String> weaknesses,
		List<ReviewTopicOutput> topicsToReview) implements LlmOutput<ReportOutput> {

	static final int MAX_STRENGTHS = 4;

	static final int MAX_WEAKNESSES = 4;

	static final int MAX_TOPICS_TO_REVIEW = 5;

	@Override
	public ReportOutput normalized() {
		return new ReportOutput(Normalize.text(summary), Normalize.texts(strengths, MAX_STRENGTHS),
				Normalize.texts(weaknesses, MAX_WEAKNESSES),
				Normalize.list(topicsToReview, MAX_TOPICS_TO_REVIEW, ReviewTopicOutput::normalized));
	}

}
