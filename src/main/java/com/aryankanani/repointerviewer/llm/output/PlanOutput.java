package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/**
 * PLAN: the topics of an interview ({@code interview-plan.st}). Capped at 8, the most questions a session can
 * have; the planner trims to the requested count and drops topics whose paths do not exist (task 1.10).
 */
public record PlanOutput(List<PlannedTopic> topics) implements LlmOutput<PlanOutput> {

	static final int MAX_TOPICS = 8;

	@Override
	public PlanOutput normalized() {
		return new PlanOutput(Normalize.list(topics, MAX_TOPICS, PlannedTopic::normalized));
	}

}
