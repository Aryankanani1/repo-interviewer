package com.aryankanani.repointerviewer.interview.planning;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.CodeNames;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectContext;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.PlanOutput;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Plans the topics of an interview (LLD §9.1 step 3): a PLAN call with the summary and the repo map, then
 * {@link TopicValidator} keeps only topics grounded in real code. The model proposes; code decides what is kept
 * (DECISIONS.md D-007). Too few kept: one more call with what was wrong (task 3.7), then the session fails.
 */
@Component
public class QuestionPlanner {

	static final String UNGROUNDED_PLAN = "Could not plan questions grounded in this repository's code. Try again.";

	private static final Logger log = LoggerFactory.getLogger(QuestionPlanner.class);

	private final LlmGateway llm;

	private final TopicValidator topicValidator;

	private final AnalysisApi analysis;

	private final int minQuestionCount;

	QuestionPlanner(LlmGateway llm, TopicValidator topicValidator, AnalysisApi analysis,
			AppProperties properties) {
		this.llm = llm;
		this.topicValidator = topicValidator;
		this.analysis = analysis;
		this.minQuestionCount = properties.interview().minQuestionCount();
	}

	/**
	 * @return between {@code app.interview.min-question-count} and {@code questionCount} grounded topics
	 * @throws DomainException {@code LLM_UNAVAILABLE} when too few topics survive the checks, or from the gateway
	 *         ({@code LLM_UNAVAILABLE}, {@code BUDGET_EXCEEDED})
	 */
	public List<PlannedTopic> plan(ProjectContext project, UUID sessionId, Difficulty difficulty,
			List<QuestionCategory> focusAreas, int questionCount) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("difficulty", difficulty.name());
		variables.put("targetRole", project.targetRole());
		variables.put("questionCount", questionCount);
		variables.put("focusAreas", focusAreas.stream().map(Enum::name).toList());
		variables.put("goal", project.goal());
		variables.put("summary", SummaryText.render(project.summary()));
		variables.put("repoMap", project.repoMap());
		variables.put("retryFeedback", null);
		CodeNames names = analysis.codeNames(project.id());
		Set<String> paths = names.paths();
		Set<String> symbols = names.symbols();

		TopicValidator.Result result = planOnce(project, sessionId, variables, paths, symbols, questionCount);
		if (result.kept().size() < minQuestionCount) {
			// Rejected (2 of 7 sessions in the 3.4 load test). A retried session has two PLAN rows in llm_call.
			log.info("Plan rejected: {} topics grounded, {} needed; retrying with feedback", result.kept().size(),
					minQuestionCount);
			variables.put("retryFeedback", retryFeedback(result, minQuestionCount));
			result = planOnce(project, sessionId, variables, paths, symbols, questionCount);
		}
		if (result.kept().size() < minQuestionCount) {
			throw new DomainException(ErrorCode.LLM_UNAVAILABLE, UNGROUNDED_PLAN);
		}
		return result.kept();
	}

	private TopicValidator.Result planOnce(ProjectContext project, UUID sessionId, Map<String, Object> variables,
			Set<String> paths, Set<String> symbols, int questionCount) {
		PlanOutput plan = llm.call(
				new LlmRequest<>(LlmPurpose.PLAN, variables, PlanOutput.class, project.id(), sessionId));
		TopicValidator.Result result = topicValidator.check(plan.topics(), paths, symbols, questionCount);
		log.info("Plan: {} topics proposed, {} grounded", plan.topics().size(), result.kept().size());
		return result;
	}

	/** What the second PLAN call reads: why each topic was dropped, and how many topics are needed. */
	static List<String> retryFeedback(TopicValidator.Result result, int needed) {
		List<String> lines = new ArrayList<>(result.problems());
		lines.add(result.kept().size() + " topics point at code that exists; at least " + needed + " are needed. "
				+ "Use only file paths and class or method names that appear in the repo map, one class per topic.");
		return lines;
	}

}
