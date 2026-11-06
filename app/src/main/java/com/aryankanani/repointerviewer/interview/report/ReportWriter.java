package com.aryankanani.repointerviewer.interview.report;

import com.aryankanani.repointerviewer.interview.CategoryScore;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.interview.InterviewReport;
import com.aryankanani.repointerviewer.interview.ReviewTopic;
import com.aryankanani.repointerviewer.interview.evaluation.GradedAnswer;
import com.aryankanani.repointerviewer.interview.evaluation.Scoring;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.ReportOutput;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the end-of-interview report (LLD §9.6). Code computes every number ({@link Scoring}: weighted overall,
 * category averages) and gives them to the model as facts; the model only writes the text around them (summary,
 * strengths, weaknesses, topics to review). Saves nothing and opens no transaction.
 */
@Component
public class ReportWriter {

	private final LlmGateway llm;

	ReportWriter(LlmGateway llm) {
		this.llm = llm;
	}

	/**
	 * @param transcript every asked question in order; at least one must be graded
	 */
	public InterviewReport write(UUID projectId, UUID sessionId, String repoName, Difficulty difficulty,
			List<TranscriptEntry> transcript) {
		List<GradedAnswer> graded = transcript.stream()
				.filter(TranscriptEntry::graded)
				.map(entry -> new GradedAnswer(entry.category(), entry.parentOrder() != null, entry.score()))
				.toList();
		BigDecimal overall = Scoring.overall(graded);
		List<CategoryScore> categories = Scoring.categoryScores(graded);

		Map<String, Object> variables = new HashMap<>();
		variables.put("repoName", repoName);
		variables.put("difficulty", difficulty.name());
		variables.put("overallScore", overall.toPlainString());
		variables.put("categoryScores", TranscriptText.categories(categories));
		variables.put("transcript", TranscriptText.render(transcript));
		ReportOutput output = llm.call(
				new LlmRequest<>(LlmPurpose.REPORT, variables, ReportOutput.class, projectId, sessionId));

		return new InterviewReport(overall.doubleValue(), output.summary(), output.strengths(), output.weaknesses(),
				categories, output.topicsToReview().stream().map(item -> new ReviewTopic(item.topic(), item.why()))
						.toList());
	}

}
