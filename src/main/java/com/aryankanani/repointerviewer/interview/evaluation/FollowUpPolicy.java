package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Whether to ask a follow-up (LLD §9.2 step 5). The model only says whether one would help; code decides, with
 * four rules that must all hold:
 * <ol>
 * <li>the model asked for one ({@code followUpNeeded});</li>
 * <li>the score is in {@code app.interview.follow-up-score-min..max} (3..7): a strong answer needs no probing, and
 * a very weak one is better served by moving on;</li>
 * <li>the topic has had fewer than {@code max-follow-ups-per-topic} (1) follow-ups;</li>
 * <li>the budget allows: a follow-up is a nice-to-have, so it may only use calls the rest of the interview does
 * not need ({@value #FOLLOW_UP_CALLS} for itself, {@value #CALLS_PER_TOPIC} per topic still to come,
 * {@value #REPORT_CALLS} for the report).</li>
 * </ol>
 */
@Component
public class FollowUpPolicy {

	/** Writing it (one retry allowed) and grading its answer. */
	static final int FOLLOW_UP_CALLS = 3;

	/** Writing a main question (one retry allowed) and grading its answer. */
	static final int CALLS_PER_TOPIC = 3;

	static final int REPORT_CALLS = 1;

	private final int scoreMin;

	private final int scoreMax;

	private final int maxFollowUpsPerTopic;

	private final int maxCallsPerSession;

	@Autowired
	FollowUpPolicy(AppProperties properties) {
		this(properties.interview().followUpScoreMin(), properties.interview().followUpScoreMax(),
				properties.interview().maxFollowUpsPerTopic(), properties.llm().maxCallsPerSession());
	}

	FollowUpPolicy(int scoreMin, int scoreMax, int maxFollowUpsPerTopic, int maxCallsPerSession) {
		this.scoreMin = scoreMin;
		this.scoreMax = scoreMax;
		this.maxFollowUpsPerTopic = maxFollowUpsPerTopic;
		this.maxCallsPerSession = maxCallsPerSession;
	}

	/**
	 * @param followUpsOnTopic follow-ups already asked on this answer's topic
	 * @param callsUsed model calls this session has made ({@code interview_session.llm_call_count})
	 * @param topicsLeft topics still PENDING
	 */
	public boolean shouldFollowUp(Evaluation evaluation, int followUpsOnTopic, int callsUsed, int topicsLeft) {
		int callsStillNeeded = FOLLOW_UP_CALLS + topicsLeft * CALLS_PER_TOPIC + REPORT_CALLS;
		return evaluation.followUpNeeded()
				&& evaluation.score() >= scoreMin && evaluation.score() <= scoreMax
				&& followUpsOnTopic < maxFollowUpsPerTopic
				&& maxCallsPerSession - callsUsed >= callsStillNeeded;
	}

}
