package com.aryankanani.repointerviewer.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The eval harness metrics (LLD §12, task 1.17) as plain functions. Code measures the model's output; the model never
 * grades itself (DECISIONS.md D-007). Unit tested in {@link EvalMetricsTest}, so every number the eval prints comes
 * from tested code.
 */
final class EvalMetrics {

	/** Two questions of one session whose content words overlap this much or more are a duplicate pair. */
	static final double DUPLICATE_JACCARD = 0.5;

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

	private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "are", "but", "not", "you", "your",
			"with", "what", "why", "how", "does", "did", "this", "that", "these", "those", "from", "into", "when",
			"which", "would", "could", "should", "there", "their", "then", "than", "its", "was", "were", "has",
			"have", "had", "can", "will", "about", "each", "any", "all", "one", "two", "both", "here", "only", "also");

	private EvalMetrics() {
	}

	/** {@code hits} out of {@code total}, printed like {@code 4/5 (80%)}. */
	record Ratio(int hits, int total) {

		double share() {
			return total == 0 ? 0 : (double) hits / total;
		}

		boolean atLeast(double target) {
			return total > 0 && share() >= target;
		}

		@Override
		public String toString() {
			return hits + "/" + total + " (" + Math.round(share() * 100) + "%)";
		}

	}

	/** A question as the duplicate check sees it. {@code parentId} is null for a main question. */
	record AskedQuestion(UUID id, UUID parentId, String text) {
	}

	static <T> Ratio count(Collection<T> items, Predicate<T> test) {
		return new Ratio((int) items.stream().filter(test).count(), items.size());
	}

	/**
	 * The names a question can mention, from chunk symbols: {@code Order.Status} gives {@code Order} and
	 * {@code Status}; {@code Order#refund(BigDecimal)} also gives {@code refund}. Merged chunks such as
	 * {@code Order#small-methods} and initializers name no method.
	 */
	static Set<String> symbolNames(Collection<String> symbols) {
		Set<String> names = new HashSet<>();
		for (String symbol : symbols) {
			int hash = symbol.indexOf('#');
			names.addAll(List.of((hash < 0 ? symbol : symbol.substring(0, hash)).split("\\.")));
			if (hash >= 0) {
				String member = symbol.substring(hash + 1);
				int paren = member.indexOf('(');
				String method = paren < 0 ? member : member.substring(0, paren);
				if (!method.contains("-") && !method.equals("initializer")) {
					names.add(method);
				}
			}
		}
		return names;
	}

	/** Specificity: the text names one of {@code names} as a whole identifier, case-sensitive. */
	static boolean mentionsAny(String text, Set<String> names) {
		Matcher identifiers = IDENTIFIER.matcher(text);
		while (identifiers.find()) {
			if (names.contains(identifiers.group())) {
				return true;
			}
		}
		return false;
	}

	/** Lower-case words of at least 3 characters, without stop words. */
	static Set<String> contentWords(String text) {
		Set<String> words = new HashSet<>();
		Matcher matcher = WORD.matcher(text.toLowerCase());
		while (matcher.find()) {
			String word = matcher.group();
			if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
				words.add(word);
			}
		}
		return words;
	}

	/** |A ∩ B| / |A ∪ B|; 0 when both are empty. */
	static double jaccard(Set<String> a, Set<String> b) {
		Set<String> union = new HashSet<>(a);
		union.addAll(b);
		if (union.isEmpty()) {
			return 0;
		}
		Set<String> common = new HashSet<>(a);
		common.retainAll(b);
		return (double) common.size() / union.size();
	}

	/**
	 * Duplicate rate: pairs of questions in one session whose content words overlap at least
	 * {@link #DUPLICATE_JACCARD}, out of all pairs. A follow-up and its own parent are not a pair: a follow-up is
	 * meant to dig into the same code.
	 */
	static Ratio duplicatePairs(List<AskedQuestion> questions) {
		int pairs = 0;
		int duplicates = 0;
		for (int i = 0; i < questions.size(); i++) {
			for (int j = i + 1; j < questions.size(); j++) {
				AskedQuestion a = questions.get(i);
				AskedQuestion b = questions.get(j);
				if (Objects.equals(a.parentId(), b.id()) || Objects.equals(b.parentId(), a.id())) {
					continue;
				}
				pairs++;
				if (jaccard(contentWords(a.text()), contentWords(b.text())) >= DUPLICATE_JACCARD) {
					duplicates++;
				}
			}
		}
		return new Ratio(duplicates, pairs);
	}

	/** Grader consistency for one answer graded twice. */
	static boolean consistent(int firstScore, int secondScore) {
		return Math.abs(firstScore - secondScore) <= 1;
	}

}
