package com.aryankanani.repointerviewer.project;

/** An analysis failure whose message is already safe and readable for the user, so it becomes the reason as is. */
class AnalysisFailure extends RuntimeException {

	AnalysisFailure(String userMessage) {
		super(userMessage);
	}

}
