package com.aryankanani.repointerviewer.interview.report;

import com.aryankanani.repointerviewer.contracts.interview.AnswerVerdict;
import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import java.util.List;

/**
 * One asked question of the interview, with its graded answer if there is one.
 *
 * @param parentOrder for a follow-up, the number of the question it follows; else null
 * @param answer null when not answered or not graded (then score, verdict and missedPoints are null too)
 */
public record TranscriptEntry(int order, QuestionCategory category, Integer parentOrder, String question,
		String answer, Integer score, AnswerVerdict verdict, List<String> missedPoints) {

	boolean graded() {
		return answer != null && score != null;
	}

}
