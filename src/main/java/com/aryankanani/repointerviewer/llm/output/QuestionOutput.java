package com.aryankanani.repointerviewer.llm.output;

import java.util.List;

/**
 * QUESTION and FOLLOW_UP: one question with its code references and hidden rubric
 * ({@code question-generation.st}, {@code follow-up-question.st}). Caps match the prompts: 1 to 3 refs, 3 to 5
 * outline points. Whether the refs exist is checked by {@code CodeRefValidator} (task 1.10).
 */
public record QuestionOutput(String question, List<CodeRefOutput> codeRefs, RubricOutput rubric,
		List<String> idealAnswerOutline) implements LlmOutput<QuestionOutput> {

	static final int MAX_CODE_REFS = 3;

	static final int MAX_OUTLINE_POINTS = 5;

	@Override
	public QuestionOutput normalized() {
		return new QuestionOutput(Normalize.text(question),
				Normalize.list(codeRefs, MAX_CODE_REFS, CodeRefOutput::normalized),
				(rubric == null ? RubricOutput.EMPTY : rubric).normalized(),
				Normalize.texts(idealAnswerOutline, MAX_OUTLINE_POINTS));
	}

}
