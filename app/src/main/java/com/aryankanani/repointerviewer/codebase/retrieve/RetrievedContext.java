package com.aryankanani.repointerviewer.codebase.retrieve;

import com.aryankanani.repointerviewer.codebase.store.RetrievedChunk;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The code chunks sent with a question prompt, within a token budget (LLD §7). A question's code refs are checked
 * against exactly these chunks ({@code CodeRefValidator}).
 */
public record RetrievedContext(List<RetrievedChunk> chunks, int totalTokens) {

	public RetrievedContext {
		chunks = List.copyOf(chunks);
	}

	/**
	 * Each chunk in its tag, with the line numbers the model must cite:
	 * <pre>
	 * &lt;code_chunk path="src/main/java/.../OrderService.java" lines="27-42" symbol="OrderService#refund"&gt;
	 * ...content...
	 * &lt;/code_chunk&gt;
	 * </pre>
	 * The repository is untrusted, so it must not be able to close the tag early and write outside it: a
	 * {@code code_chunk} tag inside the content is broken up, and quotes and angle brackets in attribute values
	 * are replaced. Line counts do not change.
	 */
	public String render() {
		return chunks.stream().map(RetrievedContext::render).collect(Collectors.joining("\n\n"));
	}

	private static String render(RetrievedChunk chunk) {
		return "<code_chunk path=\"" + attribute(chunk.path()) + "\" lines=\"" + chunk.startLine() + "-"
				+ chunk.endLine() + "\"" + (chunk.symbol() == null ? "" : " symbol=\"" + attribute(chunk.symbol()) + "\"")
				+ ">\n" + neutralize(chunk.content()) + "\n</code_chunk>";
	}

	private static String attribute(String value) {
		return value.replaceAll("[\"<>]", "_");
	}

	private static String neutralize(String content) {
		return content.replace("</code_chunk", "<\\/code_chunk").replace("<code_chunk", "<\\code_chunk");
	}

}
