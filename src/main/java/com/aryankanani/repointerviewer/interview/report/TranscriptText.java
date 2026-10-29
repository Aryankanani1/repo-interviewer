package com.aryankanani.repointerviewer.interview.report;

import com.aryankanani.repointerviewer.interview.CategoryScore;
import com.aryankanani.repointerviewer.llm.PromptMarkup;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The interview as text for the report prompt (LLD §9.6). Built by code, with each answer inside
 * {@code <candidate_answer>}: the candidate's words stay data. Every untrusted part is neutralized before it goes
 * between our tags, so the result can be passed as {@link PromptMarkup}.
 * <pre>
 * Question 1 (ARCHITECTURE): Why does OrderController#refund take a RefundRequest body?
 * &lt;candidate_answer&gt;
 * It is validated before the service is called.
 * &lt;/candidate_answer&gt;
 * Grade: 6/10, ADEQUATE. Missed: error handling
 * </pre>
 */
final class TranscriptText {

	private TranscriptText() {
	}

	static PromptMarkup render(List<TranscriptEntry> entries) {
		return new PromptMarkup(entries.stream().map(TranscriptText::render).collect(Collectors.joining("\n\n")));
	}

	private static String render(TranscriptEntry entry) {
		String heading = "Question " + entry.order() + " (" + entry.category()
				+ (entry.parentOrder() == null ? "" : ", follow-up to question " + entry.parentOrder()) + "): "
				+ PromptMarkup.neutralize(entry.question());
		if (!entry.graded()) {
			return heading + "\nNot answered.";
		}
		String missed = entry.missedPoints().isEmpty() ? "nothing"
				: PromptMarkup.neutralize(String.join("; ", entry.missedPoints()));
		return heading
				+ "\n<candidate_answer>\n" + PromptMarkup.neutralize(entry.answer()) + "\n</candidate_answer>"
				+ "\nGrade: " + entry.score() + "/10, " + entry.verdict() + ". Missed: " + missed;
	}

	/** {@code ARCHITECTURE: 7.0 over 2 questions}, one line per category. */
	static List<String> categories(List<CategoryScore> scores) {
		return scores.stream()
				.map(score -> score.category() + ": " + score.averageScore() + " over " + score.questionCount()
						+ (score.questionCount() == 1 ? " question" : " questions"))
				.toList();
	}

}
