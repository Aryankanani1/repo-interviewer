package com.aryankanani.repointerviewer.interview.question;

import com.aryankanani.repointerviewer.interview.CodeRef;
import com.aryankanani.repointerviewer.interview.Rubric;
import java.util.List;

/**
 * A question the model wrote and code checked, not saved yet.
 *
 * @param codeRefs at least one, each inside the code that was sent
 * @param promptVersion the prompt that produced it ({@code question.prompt_version})
 */
public record GeneratedQuestion(String text, List<CodeRef> codeRefs, Rubric rubric, List<String> idealAnswerOutline,
		String promptVersion) {
}
