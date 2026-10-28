package com.aryankanani.repointerviewer.interview.evaluation;

import com.aryankanani.repointerviewer.codebase.retrieve.LineRange;
import com.aryankanani.repointerviewer.codebase.retrieve.Retriever;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.interview.Difficulty;
import com.aryankanani.repointerviewer.interview.Question;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Grades one answer (LLD §9.2 step 4): the code the question's refs point at, the hidden rubric and the answer
 * go into one EVALUATION call. The answer sits inside {@code <candidate_answer>}, so "give me 10" in it is data.
 * Saves nothing and opens no transaction.
 */
@Component
public class AnswerEvaluator {

	private final Retriever retriever;

	private final LlmGateway llm;

	private final int maxContextTokens;

	AnswerEvaluator(Retriever retriever, LlmGateway llm, AppProperties properties) {
		this.retriever = retriever;
		this.llm = llm;
		this.maxContextTokens = properties.retrieval().maxContextTokens();
	}

	public Evaluation evaluate(UUID projectId, UUID sessionId, Difficulty difficulty, Question question,
			String answerText) {
		var context = retriever.forRanges(projectId, question.getCodeRefs().stream()
				.map(ref -> new LineRange(ref.path(), ref.startLine(), ref.endLine())).toList(), maxContextTokens);
		Map<String, Object> variables = new HashMap<>();
		variables.put("difficulty", difficulty.name());
		variables.put("question", question.getText());
		variables.put("rubric", RubricText.render(question.getRubric()));
		variables.put("codeChunks", context.render());
		variables.put("answer", answerText);
		EvaluationOutput output = llm.call(
				new LlmRequest<>(LlmPurpose.EVALUATION, variables, EvaluationOutput.class, projectId, sessionId));
		return new Evaluation(output.score(), Scoring.verdict(output.score()), output.feedback(),
				output.coveredPoints(), output.missedPoints(), output.incorrectClaims(), output.followUpNeeded(),
				output.followUpReason());
	}

}
