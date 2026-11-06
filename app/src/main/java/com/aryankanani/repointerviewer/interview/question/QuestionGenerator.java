package com.aryankanani.repointerviewer.interview.question;

import com.aryankanani.repointerviewer.codebase.retrieve.LineRange;
import com.aryankanani.repointerviewer.codebase.retrieve.RetrievedContext;
import com.aryankanani.repointerviewer.codebase.retrieve.Retriever;
import com.aryankanani.repointerviewer.codebase.retrieve.TopicQuery;
import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.interview.CodeRef;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.interview.InterviewTopic;
import com.aryankanani.repointerviewer.interview.Question;
import com.aryankanani.repointerviewer.interview.Rubric;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.PromptMarkup;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes questions whose code refs are checked (LLD §9.3): main questions ({@link #generate}) and follow-ups
 * ({@link #generateFollowUp}). Both get the same loop: model call, {@link CodeRefValidator}; if no ref is valid,
 * one more call with the problem and the valid ranges ({@code {retryFeedback}} in the prompt); then give up and
 * return empty.
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
	 * The main question of a topic, about the code retrieved for it.
	 *
	 * @param previousQuestions texts of the questions already asked in this session, so the model does not repeat
	 * @return empty when no attempt produced a question with a valid code ref
	 */
	public Optional<GeneratedQuestion> generate(UUID projectId, UUID sessionId, Difficulty difficulty,
			InterviewTopic topic, List<String> previousQuestions) {
		String rationale = topic.getRationale() == null ? "" : topic.getRationale();
		RetrievedContext context = retriever.forTopic(projectId, new TopicQuery(topic.getTitle() + ". " + rationale,
				topic.getTargetPaths(), topic.getTargetSymbols(), topic.getCategory() == QuestionCategory.TESTING),
				maxContextTokens);
		return withCheckedRefs(LlmPurpose.QUESTION, projectId, sessionId, context, "topic " + topic.getOrderIndex(),
				retryFeedback -> {
					Map<String, Object> variables = new HashMap<>();
					variables.put("difficulty", difficulty.name());
					variables.put("topicTitle", topic.getTitle());
					variables.put("category", topic.getCategory().name());
					variables.put("rationale", topic.getRationale());
					variables.put("codeChunks", new PromptMarkup(context.render()));
					variables.put("previousQuestions", previousQuestions);
					variables.put("retryFeedback", retryFeedback);
					return variables;
				});
	}

	/**
	 * A follow-up on a partly right answer, about the same code as the question ({@code parent}'s refs), probing
	 * what was missed.
	 *
	 * @param answer the candidate's answer to {@code parent}; untrusted, goes inside {@code <candidate_answer>}
	 */
	public Optional<GeneratedQuestion> generateFollowUp(UUID projectId, UUID sessionId, Difficulty difficulty,
			Question parent, String answer, List<String> missedPoints, String followUpReason) {
		RetrievedContext context = retriever.forRanges(projectId, parent.getCodeRefs().stream()
				.map(ref -> new LineRange(ref.path(), ref.startLine(), ref.endLine())).toList(), maxContextTokens);
		return withCheckedRefs(LlmPurpose.FOLLOW_UP, projectId, sessionId, context,
				"follow-up on question " + parent.getOrderIndex(), retryFeedback -> {
					Map<String, Object> variables = new HashMap<>();
					variables.put("difficulty", difficulty.name());
					variables.put("question", parent.getText());
					variables.put("answer", answer);
					variables.put("missedPoints", missedPoints);
					variables.put("followUpReason", followUpReason);
					variables.put("codeChunks", new PromptMarkup(context.render()));
					variables.put("retryFeedback", retryFeedback);
					return variables;
				});
	}

	/** At most {@value #MAX_ATTEMPTS} calls; the first question with a valid ref and some text wins. */
	private Optional<GeneratedQuestion> withCheckedRefs(LlmPurpose purpose, UUID projectId, UUID sessionId,
			RetrievedContext context, String what, Function<List<String>, Map<String, Object>> variables) {
		List<String> retryFeedback = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			QuestionOutput output = llm.call(new LlmRequest<>(purpose, variables.apply(retryFeedback),
					QuestionOutput.class, projectId, sessionId));
			List<CodeRef> refs = CodeRefValidator.validate(output.codeRefs(), context);
			if (!refs.isEmpty() && !output.question().isEmpty()) {
				return Optional.of(new GeneratedQuestion(output.question(), refs,
						new Rubric(output.rubric().mustMention(), output.rubric().goodToMention(),
								output.rubric().redFlags()),
						output.idealAnswerOutline(), llm.promptVersion(purpose)));
			}
			log.info("{} attempt {}: no valid code ref ({} cited, {} chunks sent)", what, attempt,
					output.codeRefs().size(), context.chunks().size());
			retryFeedback = CodeRefValidator.retryFeedback(output.question().isEmpty(), context);
		}
		return Optional.empty();
	}

}
