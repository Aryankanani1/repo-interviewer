package com.aryankanani.repointerviewer.project;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Temporary placeholder until {@code InProcessAnalysisPipeline} (task 1.9). Projects stay SUBMITTED. */
@Component
class NoOpAnalysisPipeline implements AnalysisPipeline {

	private static final Logger log = LoggerFactory.getLogger(NoOpAnalysisPipeline.class);

	@Override
	public void start(UUID projectId) {
		log.info("Analysis not started for project {}: the pipeline arrives in task 1.9", projectId);
	}

}
