package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.llm.output.ModuleOutput;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Keeps the model's summary grounded in the real repository (HLD §10.2, DECISIONS.md D-007). The interview plan
 * is built from this summary, so a class that does not exist would become a question about code that does not
 * exist.
 * <ul>
 * <li>{@code modules[].keyClasses}: names that are not in the repository are dropped.</li>
 * <li>{@code notablePatterns} and {@code riskAreas}: the prompt asks for the class or file where each one
 * appears. An item that names none from the repository is dropped. Other names in it may stay (a sentence like
 * "OrderRepository extends JpaRepository" is fine: JpaRepository is the framework's).</li>
 * </ul>
 * Names are compared exactly. {@code OrderService#refund} counts as {@code OrderService}, and trailing
 * punctuation and {@code ()} are ignored.
 */
@Component
class SummaryValidator {

	/** Something that could name code: {@code OrderService}, {@code Order.Status}, {@code OrderService#refund()},
	 * {@code application.yml}. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.#]*(\\(\\))?");

	/**
	 * @param knownNames from {@code CodebaseQueries.knownNames}
	 */
	RepoSummary validate(SummaryOutput summary, Set<String> knownNames) {
		return new RepoSummary(
				summary.oneLiner(),
				summary.architectureStyle(),
				summary.techStack(),
				summary.modules().stream().map(module -> module(module, knownNames)).toList(),
				grounded(summary.notablePatterns(), knownNames),
				grounded(summary.riskAreas(), knownNames));
	}

	private static RepoSummary.ModuleSummary module(ModuleOutput module, Set<String> knownNames) {
		return new RepoSummary.ModuleSummary(module.name(), module.responsibility(),
				module.keyClasses().stream().filter(name -> isKnown(name, knownNames)).toList());
	}

	private static List<String> grounded(List<String> items, Set<String> knownNames) {
		return items.stream().filter(item -> mentionsKnown(item, knownNames)).toList();
	}

	static boolean mentionsKnown(String text, Set<String> knownNames) {
		Matcher matcher = NAME.matcher(text);
		while (matcher.find()) {
			if (isKnown(matcher.group(), knownNames)) {
				return true;
			}
		}
		return false;
	}

	static boolean isKnown(String name, Set<String> knownNames) {
		String cleaned = name.strip().replaceAll("\\(\\)$", "").replaceAll("[.,;:]+$", "");
		int hash = cleaned.indexOf('#');
		String type = hash < 0 ? cleaned : cleaned.substring(0, hash);
		return knownNames.contains(cleaned) || knownNames.contains(type);
	}

}
