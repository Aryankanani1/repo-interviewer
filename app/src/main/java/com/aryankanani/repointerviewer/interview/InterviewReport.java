package com.aryankanani.repointerviewer.interview;

import java.util.List;

/**
 * The end-of-interview report (LLD §9.6), stored as JSON in {@code interview_session.report}. The numbers come from
 * code ({@code Scoring}); the text comes from the model. Field names match the GraphQL type {@code InterviewReport}.
 *
 * @param overallScore 0..10, one decimal, weighted (main question 1.0, follow-up 0.5)
 */
public record InterviewReport(double overallScore, String summary, List<String> strengths, List<String> weaknesses,
		List<CategoryScore> categoryScores, List<ReviewTopic> topicsToReview) {
}
