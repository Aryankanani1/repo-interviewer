package com.aryankanani.repointerviewer.interview.question;

import com.aryankanani.repointerviewer.codebase.retrieve.RetrievedContext;
import com.aryankanani.repointerviewer.codebase.store.RetrievedChunk;
import com.aryankanani.repointerviewer.interview.CodeRef;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the code refs a question cites against the chunks that were actually sent with the prompt (LLD §9.3).
 * The model can only have read those, so a ref anywhere else is invented.
 *
 * <p>A ref is valid when its path is a sent chunk's path and its line range overlaps that chunk's range. It is
 * then cut to the chunk's range (the chunk with the largest overlap, if several), so it never points at lines the
 * model did not see. Duplicates are dropped.
 */
final class CodeRefValidator {

	private CodeRefValidator() {
	}

	static List<CodeRef> validate(List<CodeRefOutput> refs, RetrievedContext context) {
		Set<CodeRef> valid = new LinkedHashSet<>();
		for (CodeRefOutput ref : refs) {
			if (ref.startLine() < 1 || ref.endLine() < ref.startLine()) {
				continue;
			}
			context.chunks().stream()
					.filter(chunk -> chunk.path().equals(ref.path()) && overlap(chunk, ref) > 0)
					.max(Comparator.comparingInt(chunk -> overlap(chunk, ref)))
					.ifPresent(chunk -> valid.add(new CodeRef(ref.path(), Math.max(ref.startLine(), chunk.startLine()),
							Math.min(ref.endLine(), chunk.endLine()))));
		}
		return List.copyOf(valid);
	}

	/** For the retry: what was wrong, and the ranges that would have been valid. */
	static List<String> retryFeedback(boolean emptyQuestion, RetrievedContext context) {
		List<String> problems = new ArrayList<>();
		if (emptyQuestion) {
			problems.add("The question text was empty.");
		}
		problems.add("No codeRef was inside the code_chunk ranges above. The only valid ranges are:");
		context.chunks().forEach(chunk -> problems.add(chunk.path() + " lines " + chunk.startLine() + "-" + chunk.endLine()));
		return problems;
	}

	/** Lines in both ranges; 0 or less when they do not touch. */
	private static int overlap(RetrievedChunk chunk, CodeRefOutput ref) {
		return Math.min(chunk.endLine(), ref.endLine()) - Math.max(chunk.startLine(), ref.startLine()) + 1;
	}

}
