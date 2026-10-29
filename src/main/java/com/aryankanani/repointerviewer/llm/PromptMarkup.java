package com.aryankanani.repointerviewer.llm;

import java.util.regex.Pattern;

/**
 * A prompt value that our own code built and that contains our own tags, such as the rendered
 * {@code <code_chunk>} blocks or a transcript with {@code <candidate_answer>} blocks. {@link PromptTemplates} inserts
 * it as is. Every other value is treated as untrusted and has these tags broken up ({@link #neutralize}), so text
 * from a repository or a candidate can never close one of our tags early and write instructions outside it.
 *
 * <p>Whoever builds a {@code PromptMarkup} must {@link #neutralize} the untrusted parts it puts inside its tags.
 */
public record PromptMarkup(String text) {

	/** The tags whose content the system prompt declares untrusted. */
	private static final Pattern UNTRUSTED_TAG = Pattern.compile(
			"<(/?)(repo_map|repo_content|code_chunk|candidate_context|candidate_answer)", Pattern.CASE_INSENSITIVE);

	/**
	 * {@code </candidate_answer>} becomes {@code <\/candidate_answer>} and {@code <code_chunk} becomes
	 * {@code <\code_chunk}, in any letter case: still readable, no longer a tag. Line counts do not change.
	 */
	public static String neutralize(String untrusted) {
		return UNTRUSTED_TAG.matcher(untrusted).replaceAll("<\\\\$1$2");
	}

}
