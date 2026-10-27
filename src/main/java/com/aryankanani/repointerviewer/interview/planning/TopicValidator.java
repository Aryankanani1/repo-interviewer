package com.aryankanani.repointerviewer.interview.planning;

import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Keeps only plan topics that point at code that exists (LLD §9.1, HLD §10.2). A topic about an invented file
 * would become a question about code the candidate never wrote.
 * <ul>
 * <li>A path is kept if it is a file of the project (a leading {@code ./} or {@code /} is ignored).</li>
 * <li>A symbol is kept if it is a chunk symbol, or the start of one: {@code OrderService} covers its methods,
 * {@code OrderService#refund} covers {@code OrderService#refund(UUID,RefundRequest)} overloads and
 * {@code #part2} windows. An unknown method of a real class falls back to the class: a small method may have
 * been merged into a {@code #small-methods} chunk.</li>
 * <li>A topic left with no path and no symbol is dropped, and so is one with no category or title.</li>
 * <li>A topic whose main class (first symbol, else first file) repeats an earlier topic's is dropped: the
 * prompt asks for different parts of the system.</li>
 * <li>At most the requested number of topics, in the plan's order.</li>
 * </ul>
 */
@Component
class TopicValidator {

	/**
	 * @param paths every file path of the project
	 * @param symbols every chunk symbol of the project
	 */
	List<PlannedTopic> validate(List<PlannedTopic> topics, Set<String> paths, Set<String> symbols, int maxTopics) {
		List<PlannedTopic> kept = new ArrayList<>();
		Set<String> mainTargets = new HashSet<>();
		for (PlannedTopic topic : topics) {
			if (kept.size() == maxTopics) {
				break;
			}
			if (topic.category() == null || topic.title().isEmpty()) {
				continue;
			}
			List<String> validPaths = topic.targetPaths().stream()
					.map(TopicValidator::normalizePath)
					.filter(paths::contains)
					.distinct()
					.toList();
			List<String> validSymbols = topic.targetSymbols().stream()
					.map(symbol -> resolve(symbol, symbols))
					.filter(Objects::nonNull)
					.distinct()
					.toList();
			if (validPaths.isEmpty() && validSymbols.isEmpty()) {
				continue;
			}
			String main = validSymbols.isEmpty() ? typeOfPath(validPaths.getFirst()) : typeOf(validSymbols.getFirst());
			if (mainTargets.add(main)) {
				kept.add(new PlannedTopic(topic.title(), topic.category(), validPaths, validSymbols, topic.rationale()));
			}
		}
		return kept;
	}

	/** The symbol as given if it exists, its class if only that exists, else null. */
	static String resolve(String symbol, Set<String> symbols) {
		String name = symbol.strip();
		if (name.isEmpty()) {
			return null;
		}
		if (symbols.contains(name)
				|| symbols.stream().anyMatch(known -> known.startsWith(name + "#") || known.startsWith(name + "("))) {
			return name;
		}
		int hash = name.indexOf('#');
		if (hash > 0 && symbols.contains(name.substring(0, hash))) {
			return name.substring(0, hash);
		}
		return null;
	}

	private static String normalizePath(String path) {
		String normalized = path.strip();
		while (normalized.startsWith("./") || normalized.startsWith("/")) {
			normalized = normalized.substring(normalized.startsWith("./") ? 2 : 1);
		}
		return normalized;
	}

	private static String typeOf(String symbol) {
		int hash = symbol.indexOf('#');
		return hash < 0 ? symbol : symbol.substring(0, hash);
	}

	/** {@code src/.../OrderService.java} → {@code OrderService}, so a path and a symbol of one class collide. */
	private static String typeOfPath(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : path;
	}

}
