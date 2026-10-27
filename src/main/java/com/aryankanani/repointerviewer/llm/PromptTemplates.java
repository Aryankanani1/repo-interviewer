package com.aryankanani.repointerviewer.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The prompt files in {@code src/main/resources/prompts/} (LLD §8.3): loaded and checked once at startup,
 * filled in for each call.
 *
 * <p>Strict on purpose, so a typo fails loudly instead of sending a broken prompt:
 * <ul>
 * <li>At startup, a file may only contain {@code {name}} placeholders. Any other curly brace stops the app: the
 * template engine (StringTemplate) would read it as the start of an expression.</li>
 * <li>When rendering, the variables must match the placeholders exactly: a missing one or an unknown one is an
 * error, not an empty spot in the prompt.</li>
 * </ul>
 * Values: null and an empty list become {@value #NOT_PROVIDED}, a list becomes one {@code - item} line per
 * element, anything else its {@code toString()}. Values are inserted as text and never read as template code, so
 * code with braces in it is safe.
 *
 * <p>{@code prompt_version} ({@link #version}) is the template name plus the first 8 hex characters of the
 * SHA-256 of the system prompt and the template together, for example {@code repo-summary@1a2b3c4d}. Editing
 * either file changes it, so results in {@code llm_call} can be compared before and after a prompt change.
 */
@Component
public class PromptTemplates {

	static final String SYSTEM = "system-interviewer";

	static final String NOT_PROVIDED = "(not provided)";

	private static final String LOCATION = "classpath:prompts/*.st";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

	private record Template(String text, Set<String> placeholders, String version) {
	}

	private final Map<String, Template> templates;

	@Autowired
	PromptTemplates() {
		this(load());
	}

	/** @param texts template name (file name without {@code .st}) to file content */
	PromptTemplates(Map<String, String> texts) {
		String system = texts.get(SYSTEM);
		if (system == null) {
			throw new IllegalStateException("prompts/" + SYSTEM + ".st is missing");
		}
		Map<String, Template> checked = new HashMap<>();
		texts.forEach((name, text) -> checked.put(name,
				new Template(text, placeholders(name, text), name + "@" + shortHash(system + "\n" + text))));
		if (!checked.get(SYSTEM).placeholders().isEmpty()) {
			throw new IllegalStateException("prompts/" + SYSTEM + ".st must not have placeholders");
		}
		this.templates = Map.copyOf(checked);
	}

	/** The shared system message, the same for every purpose. */
	public String system() {
		return template(SYSTEM).text();
	}

	/** For example {@code repo-summary@1a2b3c4d}; at most 40 characters ({@code llm_call.prompt_version}). */
	public String version(String name) {
		return template(name).version();
	}

	/**
	 * @throws IllegalArgumentException a placeholder has no value, or a value has no placeholder
	 */
	public String render(String name, Map<String, Object> variables) {
		Template template = template(name);
		Set<String> missing = new TreeSet<>(template.placeholders());
		missing.removeAll(variables.keySet());
		Set<String> unknown = new TreeSet<>(variables.keySet());
		unknown.removeAll(template.placeholders());
		if (!missing.isEmpty() || !unknown.isEmpty()) {
			throw new IllegalArgumentException("Template " + name + ": missing variables " + missing
					+ ", unknown variables " + unknown);
		}
		Map<String, Object> values = new HashMap<>();
		variables.forEach((key, value) -> values.put(key, asText(value)));
		return PromptTemplate.builder().template(template.text()).variables(values).build().render();
	}

	private Template template(String name) {
		Template template = templates.get(name);
		if (template == null) {
			throw new IllegalArgumentException("No prompt template prompts/" + name + ".st");
		}
		return template;
	}

	static String asText(Object value) {
		if (value == null) {
			return NOT_PROVIDED;
		}
		if (value instanceof Collection<?> items) {
			return items.isEmpty() ? NOT_PROVIDED
					: items.stream().map(item -> "- " + asText(item)).collect(Collectors.joining("\n"));
		}
		return value.toString();
	}

	/** The placeholders in order of appearance; fails if any other brace is left. */
	private static Set<String> placeholders(String name, String text) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		String rest = PLACEHOLDER.matcher(text).replaceAll("");
		int stray = indexOfBrace(rest);
		if (stray >= 0) {
			throw new IllegalStateException("prompts/" + name + ".st: only {name} placeholders are allowed, found '"
					+ rest.charAt(stray) + "' near \"" + rest.substring(Math.max(0, stray - 20), stray + 1) + "\"");
		}
		return names;
	}

	private static int indexOfBrace(String text) {
		int open = text.indexOf('{');
		int close = text.indexOf('}');
		return open < 0 ? close : (close < 0 ? open : Math.min(open, close));
	}

	private static String shortHash(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash).substring(0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);   // every JVM has SHA-256
		}
	}

	private static Map<String, String> load() {
		try {
			Map<String, String> texts = new HashMap<>();
			for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
				String fileName = resource.getFilename();
				texts.put(fileName.substring(0, fileName.length() - ".st".length()),
						resource.getContentAsString(StandardCharsets.UTF_8));
			}
			return texts;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Every template name, for tests. */
	List<String> names() {
		return templates.keySet().stream().sorted().toList();
	}

}
