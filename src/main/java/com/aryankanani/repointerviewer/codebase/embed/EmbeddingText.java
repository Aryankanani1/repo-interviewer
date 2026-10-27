package com.aryankanani.repointerviewer.codebase.embed;

import com.aryankanani.repointerviewer.codebase.store.PendingChunk;
import java.util.regex.Pattern;

/**
 * The text that gets embedded, which is not exactly what is stored (LLD §6.6). A short header with the file,
 * class and method names comes before the content, so a chunk is found by what it is called as well as by
 * what it does.
 *
 * <p>{@code nomic-embed-text} was trained with task prefixes: {@code search_document:} for stored text and
 * {@code search_query:} for what we search with. Without them its results get noticeably worse.
 */
final class EmbeddingText {

	/** {@code #part2}, {@code #part3}, ... added to windows of a long method (LineWindows). */
	private static final Pattern PART_SUFFIX = Pattern.compile("#part\\d+$");

	private EmbeddingText() {
	}

	/**
	 * <pre>
	 * search_document: File: src/main/java/com/example/shop/order/OrderService.java
	 * Class: OrderService
	 * Method: refund
	 * &lt;content&gt;
	 * </pre>
	 * A type header has no Method line, and a text chunk (no symbol) has no Class line either.
	 */
	static String document(PendingChunk chunk) {
		StringBuilder text = new StringBuilder("search_document: File: ").append(chunk.path()).append('\n');
		if (chunk.symbol() != null) {
			String symbol = PART_SUFFIX.matcher(chunk.symbol()).replaceFirst("");
			int hash = symbol.indexOf('#');
			text.append("Class: ").append(hash < 0 ? symbol : symbol.substring(0, hash)).append('\n');
			String method = hash < 0 ? null : symbol.substring(hash + 1);
			// A run of small methods has a made-up name, not a method name worth matching on.
			if (method != null && !method.startsWith("small-methods")) {
				text.append("Method: ").append(method).append('\n');
			}
		}
		return text.append(chunk.content()).toString();
	}

	static String query(String text) {
		return "search_query: " + text;
	}

}
