package com.aryankanani.repointerviewer.interview.planning;

import com.aryankanani.repointerviewer.codebase.CodebaseQueries;
import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.PlanOutput;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import com.aryankanani.repointerviewer.project.ProjectContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Plans the topics of an interview (LLD §9.1 step 3): one PLAN call with the summary and the repo map, then
 * {@link TopicValidator} keeps only topics grounded in real code. The model proposes; code decides what is kept
 * (DECISIONS.md D-007).
 */
@Component
public class QuestionPlanner {

	static final String UNGROUNDED_PLAN = "Could not plan questions grounded in this repository's code. Try again.";

	private static final Logger log = LoggerFactory.getLogger(QuestionPlanner.class);

	private final LlmGateway llm;

	private final TopicValidator topicValidator;

	private final CodebaseQueries codebaseQueries;

	private final int minQuestionCount;

	QuestionPlanner(LlmGateway llm, TopicValidator topicValidator, CodebaseQueries codebaseQueries,
			AppProperties properties) {
		this.llm = llm;
		this.topicValidator = topicValidator;
		this.codebaseQueries = codebaseQueries;
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
		PlanOutput plan = llm.call(new LlmRequest<>(LlmPurpose.PLAN, variables, PlanOutput.class, project.id(), sessionId));

		List<PlannedTopic> topics = topicValidator.validate(plan.topics(), codebaseQueries.paths(project.id()),
				codebaseQueries.symbols(project.id()), questionCount);
		log.info("Plan: {} topics proposed, {} grounded", plan.topics().size(), topics.size());
		if (topics.size() < minQuestionCount) {
			throw new DomainException(ErrorCode.LLM_UNAVAILABLE, UNGROUNDED_PLAN);
		}
		return topics;
	}

}
