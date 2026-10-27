package com.aryankanani.repointerviewer.codebase.scan;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Replaces secrets with {@code <REDACTED:type>} before a file is chunked (LLD §6.7), so neither the stored
 * chunks, the embeddings nor any prompt ever contains them.
 *
 * <p>It runs on the whole file, not on each chunk: a secret that a chunk window would cut in half is still
 * seen whole here. It never changes the number of lines, so every line number still matches the repository.
 *
 * <p>Two kinds of rules:
 * <ul>
 * <li>Token formats, in every file: private key blocks, AWS access key ids, GitHub tokens, Anthropic and other
 * {@code sk-} keys, and JWT-looking strings.</li>
 * <li>Assignments in YAML and properties files: a key containing {@code password}, {@code secret}, {@code token}
 * or {@code api-key} with a literal value. A {@code ${NAME}} placeholder is kept; the default in
 * {@code ${NAME:default}} is a hard-coded value and is redacted. Numbers, booleans and durations are kept
 * ({@code max-tokens: 1024} is not a secret).</li>
 * </ul>
 */
@Component
public class SecretRedactor {

	private static final Pattern PRIVATE_KEY = Pattern.compile(
			"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)", Pattern.DOTALL);

	private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");

	private static final Pattern GITHUB_TOKEN = Pattern
			.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})\\b");

	private static final Pattern ANTHROPIC_KEY = Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}");

	private static final Pattern SK_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}");

	private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

	/**
	 * {@code key: value} or {@code key=value} on one line, optionally indented, commented out ({@code #}, {@code !})
	 * or a list item ({@code - POSTGRES_PASSWORD=...} in a Compose file).
	 */
	private static final Pattern ASSIGNMENT = Pattern.compile(
			"^(?<prefix>[ \\t]*(?:[#!][ \\t]*)?(?:-[ \\t]+)?[\"']?)"
					+ "(?<key>[A-Za-z0-9_.\\-]*(?:password|secret|token|api[-_]?key)[A-Za-z0-9_.\\-]*)"
					+ "(?<separator>[\"']?[ \\t]*[:=][ \\t]*)(?<value>[^\\r\\n]*)$",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

	private static final Pattern SECRET_WORD = Pattern.compile("password|secret|token|api[-_]?key",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern PLACEHOLDER_WITH_DEFAULT = Pattern.compile("\\$\\{([^:}]+):(.*)}");

	/** Values that cannot be credentials: numbers, booleans, durations, YAML nulls and block indicators. */
	private static final Pattern HARMLESS_VALUE = Pattern.compile(
			"-?\\d+(\\.\\d+)?|\\d+(ns|us|ms|s|m|h|d)|true|false|null|~|[|>][+-]?", Pattern.CASE_INSENSITIVE);

	public RedactedFile redact(ScannedFile file) {
		Redaction redaction = new Redaction(file.content());
		redaction.replace(PRIVATE_KEY, match -> placeholder("private_key") + lineBreaksIn(match.group()));
		redaction.replace(AWS_ACCESS_KEY, match -> placeholder("aws_access_key"));
		redaction.replace(GITHUB_TOKEN, match -> placeholder("github_token"));
		redaction.replace(ANTHROPIC_KEY, match -> placeholder("anthropic_key"));
		redaction.replace(SK_KEY, match -> placeholder("api_key"));
		redaction.replace(JWT, match -> placeholder("jwt"));
		if (file.language().equals("yaml") || file.language().equals("properties")) {
			redaction.replace(ASSIGNMENT, SecretRedactor::redactAssignment);
		}
		return new RedactedFile(file.path(), file.language(), redaction.text, redaction.count);
	}

	private static String redactAssignment(MatchResult match) {
		String prefix = match.group("prefix");
		String separator = match.group("separator");
		String value = match.group("value").strip();
		String trailingSpace = match.group("value").substring(match.group("value").stripTrailing().length());
		// - "POSTGRES_PASSWORD=x": the whole list item is quoted, so the closing quote is not part of the value.
		String closingQuote = "";
		if (!prefix.isEmpty() && "\"'".indexOf(prefix.charAt(prefix.length() - 1)) >= 0
				&& !separator.startsWith(prefix.substring(prefix.length() - 1))
				&& value.endsWith(prefix.substring(prefix.length() - 1))) {
			closingQuote = value.substring(value.length() - 1);
			value = value.substring(0, value.length() - 1);
		}
		return prefix + match.group("key") + separator + redactValue(value, secretType(match.group("key")))
				+ closingQuote + trailingSpace;
	}

	/** @param value the value without surrounding whitespace */
	private static String redactValue(String value, String type) {
		String unquoted = unquote(value);
		if (unquoted.isEmpty() || unquoted.startsWith("<REDACTED:") || HARMLESS_VALUE.matcher(unquoted).matches()) {
			return value;
		}
		Matcher placeholder = PLACEHOLDER_WITH_DEFAULT.matcher(unquoted);
		if (placeholder.matches()) {
			String fallback = placeholder.group(2);
			if (fallback.isEmpty() || fallback.startsWith("${") || HARMLESS_VALUE.matcher(fallback).matches()) {
				return value;
			}
			return value.replace(unquoted, "${" + placeholder.group(1) + ":" + placeholder(type) + "}");
		}
		if (unquoted.startsWith("${") && unquoted.endsWith("}")) {
			return value;   // ${NAME}: the real value comes from the environment
		}
		return value.equals(unquoted) ? placeholder(type) : value.replace(unquoted, placeholder(type));
	}

	/** {@code "abc"} and {@code 'abc'} become {@code abc}; anything else is returned as is. */
	private static String unquote(String value) {
		boolean quoted = value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
				&& value.charAt(value.length() - 1) == value.charAt(0);
		return quoted ? value.substring(1, value.length() - 1) : value;
	}

	/** {@code spring.datasource.password} gives {@code password}, {@code stripe.api-key} gives {@code api_key}. */
	private static String secretType(String key) {
		Matcher word = SECRET_WORD.matcher(key);
		word.find();
		String type = word.group().toLowerCase(Locale.ROOT);
		return type.startsWith("api") ? "api_key" : type;
	}

	private static String placeholder(String type) {
		return "<REDACTED:" + type + ">";
	}

	/** Keeps the line count: a 30-line key block becomes one marker plus 29 empty lines. */
	private static String lineBreaksIn(String text) {
		return "\n".repeat((int) text.chars().filter(c -> c == '\n').count());
	}

	/** The text being redacted, and how many replacements changed it. */
	private static final class Redaction {

		private String text;

		private int count;

		Redaction(String text) {
			this.text = text;
		}

		void replace(Pattern pattern, Function<MatchResult, String> replacement) {
			Matcher matcher = pattern.matcher(text);
			StringBuilder result = new StringBuilder();
			while (matcher.find()) {
				String replaced = replacement.apply(matcher.toMatchResult());
				if (!replaced.equals(matcher.group())) {
					count++;
				}
				matcher.appendReplacement(result, Matcher.quoteReplacement(replaced));
			}
			matcher.appendTail(result);
			text = result.toString();
		}

	}

}
