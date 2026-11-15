package com.aryankanani.repointerviewer.codebase;

import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The extra text for the summary prompt (LLD §6.9): the build files' dependencies and the start of the README.
 * Both come from redacted text, like everything that goes into a prompt.
 *
 * <p>Only the dependency coordinates, not the whole build file: they say which frameworks the code uses (the
 * summary's {@code techStack}) in a few hundred tokens instead of thousands.
 */
final class SummarySources {

	static final int README_LINES = 150;

	static final int MAX_DEPENDENCIES = 100;

	private static final Pattern MAVEN_PARENT = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

	private static final Pattern MAVEN_DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

	private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
			"^\\s*(implementation|api|compileOnly|runtimeOnly|annotationProcessor|developmentOnly|testImplementation"
					+ "|testRuntimeOnly|testCompileOnly)\\s*\\(?\\s*(?:platform\\s*\\(\\s*)?[\"']([^\"']+)[\"']",
			Pattern.MULTILINE);

	private SummarySources() {
	}

	/**
	 * <pre>
	 * pom.xml
	 * - parent org.springframework.boot:spring-boot-starter-parent:4.1.2
	 * - org.springframework.boot:spring-boot-starter-webmvc
	 * - org.springframework.boot:spring-boot-starter-test (test)
	 * </pre>
	 * One block per {@code pom.xml} or Gradle build script, at most {@value #MAX_DEPENDENCIES} lines in total.
	 *
	 * @return null when no build file declares a dependency
	 */
	static String buildFiles(List<RedactedFile> files) {
		List<String> blocks = new ArrayList<>();
		int budget = MAX_DEPENDENCIES;
		for (RedactedFile file : sortedByPath(files)) {
			String name = fileName(file.path());
			List<String> dependencies = name.equals("pom.xml") ? maven(file.content())
					: (name.equals("build.gradle") || name.equals("build.gradle.kts")) ? gradle(file.content())
							: List.of();
			if (dependencies.isEmpty() || budget == 0) {
				continue;
			}
			List<String> shown = dependencies.subList(0, Math.min(budget, dependencies.size()));
			budget -= shown.size();
			blocks.add(file.path() + "\n" + shown.stream().map(line -> "- " + line).collect(Collectors.joining("\n")));
		}
		return blocks.isEmpty() ? null : String.join("\n\n", blocks);
	}

	/** The first {@value #README_LINES} lines of the README closest to the root; null when there is none. */
	static String readme(List<RedactedFile> files) {
		return files.stream()
				.filter(file -> {
					String name = fileName(file.path());
					return name.startsWith("readme") && name.endsWith(".md");
				})
				.min(Comparator.comparingInt((RedactedFile file) -> depth(file.path())).thenComparing(RedactedFile::path))
				.map(file -> file.content().lines().limit(README_LINES).collect(Collectors.joining("\n")))
				.orElse(null);
	}

	static List<String> maven(String pom) {
		Set<String> lines = new LinkedHashSet<>();
		Matcher parent = MAVEN_PARENT.matcher(pom);
		if (parent.find()) {
			String version = tag(parent.group(1), "version");
			lines.add("parent " + tag(parent.group(1), "groupId") + ":" + tag(parent.group(1), "artifactId")
					+ (version.isEmpty() ? "" : ":" + version));
		}
		Matcher dependency = MAVEN_DEPENDENCY.matcher(pom);
		while (dependency.find()) {
			String body = dependency.group(1);
			String artifact = tag(body, "artifactId");
			if (!artifact.isEmpty()) {
				String scope = tag(body, "scope");
				lines.add(tag(body, "groupId") + ":" + artifact + (scope.equals("test") ? " (test)" : ""));
			}
		}
		return List.copyOf(lines);
	}

	static List<String> gradle(String script) {
		Set<String> lines = new LinkedHashSet<>();
		Matcher dependency = GRADLE_DEPENDENCY.matcher(script);
		while (dependency.find()) {
			lines.add(dependency.group(2) + (dependency.group(1).startsWith("test") ? " (test)" : ""));
		}
		return List.copyOf(lines);
	}

	/** The text of the first {@code <name>} element, trimmed; empty when there is none. */
	private static String tag(String xml, String name) {
		Matcher matcher = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static List<RedactedFile> sortedByPath(List<RedactedFile> files) {
		return files.stream().sorted(Comparator.comparing(RedactedFile::path)).toList();
	}

	private static String fileName(String path) {
		return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
	}

	private static int depth(String path) {
		return (int) path.chars().filter(c -> c == '/').count();
	}

}
