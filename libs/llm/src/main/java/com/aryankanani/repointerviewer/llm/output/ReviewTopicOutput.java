package com.aryankanani.repointerviewer.llm.output;

/** One item of {@link ReportOutput#topicsToReview()}. */
public record ReviewTopicOutput(String topic, String why) {

	ReviewTopicOutput normalized() {
		return new ReviewTopicOutput(Normalize.text(topic), Normalize.text(why));
	}

}
