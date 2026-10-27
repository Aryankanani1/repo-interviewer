package com.aryankanani.repointerviewer.interview.question;

import com.aryankanani.repointerviewer.codebase.retrieve.RetrievedContext;
import com.aryankanani.repointerviewer.codebase.retrieve.Retriever;
import com.aryankanani.repointerviewer.codebase.retrieve.TopicQuery;
import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.interview.CodeRef;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.interview.InterviewTopic;
import com.aryankanani.repointerviewer.interview.Rubric;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the main question of a topic (LLD §9.3): retrieve the code, QUESTION call, check the refs. If no ref is
 * valid, one more call with the problem and the valid ranges ({@code {retryFeedback}} in the prompt); if that
 * fails too, there is no question for this topic and the caller skips it.
 *
 * <p>Saves nothing and opens no transaction: it only talks to the retriever and the model.
 */
@Component
public class QuestionGenerator {

	static final int MAX_ATTEMPTS = 2;

	private static final Logger log = LoggerFactory.getLogger(QuestionGenerator.class);

	private final Retriever retriever;

	private final LlmGateway llm;

	private final int maxContextTokens;

	QuestionGenerator(Retriever retriever, LlmGateway llm, AppProperties properties) {
		this.retriever = retriever;
		this.llm = llm;
		this.maxContextTokens = properties.retrieval().maxContextTokens();
	}

	/**
	 * @param previousQuestions texts of the questions already asked in this session, so the model does not repeat
	 * @return empty when no attempt produced a question with a valid code ref
	 */
	public Optional<GeneratedQuestion> generate(UUID projectId, UUID sessionId, Difficulty difficulty,
			InterviewTopic topic, List<String> previousQuestions) {
		String rationale = topic.getRationale() == null ? "" : topic.getRationale();
		RetrievedContext context = retriever.forTopic(projectId, new TopicQuery(topic.getTitle() + ". " + rationale,
				topic.getTargetPaths(), topic.getTargetSymbols(), topic.getCategory() == QuestionCategory.TESTING),
				maxContextTokens);

		List<String> retryFeedback = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			Map<String, Object> variables = new HashMap<>();
			variables.put("difficulty", difficulty.name());
			variables.put("topicTitle", topic.getTitle());
			variables.put("category", topic.getCategory().name());
			variables.put("rationale", topic.getRationale());
			variables.put("codeChunks", context.render());
			variables.put("previousQuestions", previousQuestions);
			variables.put("retryFeedback", retryFeedback);
			QuestionOutput output = llm.call(
					new LlmRequest<>(LlmPurpose.QUESTION, variables, QuestionOutput.class, projectId, sessionId));

			List<CodeRef> refs = CodeRefValidator.validate(output.codeRefs(), context);
			if (!refs.isEmpty() && !output.question().isEmpty()) {
				return Optional.of(new GeneratedQuestion(output.question(), refs,
						new Rubric(output.rubric().mustMention(), output.rubric().goodToMention(),
								output.rubric().redFlags()),
						output.idealAnswerOutline(), llm.promptVersion(LlmPurpose.QUESTION)));
			}
			log.info("Topic {} attempt {}: no valid code ref ({} cited, {} chunks sent)", topic.getOrderIndex(),
					attempt, output.codeRefs().size(), context.chunks().size());
			retryFeedback = CodeRefValidator.retryFeedback(output.question().isEmpty(), context);
		}
		return Optional.empty();
	}

}
