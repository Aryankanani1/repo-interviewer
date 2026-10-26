package com.aryankanani.repointerviewer.codebase.scan;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Path and file-name rules from LLD §6.5, shared by {@link FileScanner} (what to read) and
 * {@link FileClassifier} (TEST, MIGRATION, BUILD, CONFIG). Paths are repo-relative with forward slashes.
 * Everything is compared in lowercase: READMEs and Dockerfiles come in every spelling.
 */
final class FilePatterns {

	static final String JAVA = "java";

	/** Skipped wherever they appear: version control, IDE settings, Gradle cache, JavaScript dependencies. */
	private static final Set<String> ALWAYS_IGNORED_DIRS = Set.of(".git", ".idea", ".vscode", ".gradle",
			"node_modules");

	/**
	 * Build output and vendored code, skipped only outside {@code src/}. Inside it the same names are ordinary
	 * Java packages ({@code com.acme.vendor}), and skipping them would silently drop real code.
	 */
	private static final Set<String> OUTPUT_DIRS = Set.of("target", "build", "out", "dist", "vendor");

	private static final List<String> SECRET_PREFIXES = List.of(".env", "credentials", "id_rsa", "id_dsa",
			"id_ecdsa", "id_ed25519");

	private static final List<String> SECRET_SUFFIXES = List.of(".pem", ".key", ".p12", ".jks", ".keystore");

	private static final Pattern GRADLE_SCRIPT = Pattern.compile("(build|settings)\\.gradle(\\.kts)?");

	/** compose.yaml (the current default name), docker-compose.yml, compose.override.yml, ... */
	private static final Pattern COMPOSE_FILE = Pattern.compile("(docker-)?compose([.-].*)?\\.ya?ml");

	private static final Pattern WORKFLOW = Pattern.compile("\\.github/workflows/[^/]+\\.ya?ml");

	/** application.yml, application-prod.yaml, application.properties, ... */
	private static final Pattern APPLICATION_CONFIG = Pattern.compile("application.*\\.(ya?ml|properties)");

	private FilePatterns() {
	}

	static boolean isIgnoredDirectory(String dirPath) {
		List<String> segments = segments(dirPath);
		String name = segments.getLast();
		List<String> parents = segments.subList(0, segments.size() - 1);
		if (ALWAYS_IGNORED_DIRS.contains(name)) {
			return true;
		}
		if (name.equals("wrapper") && !parents.isEmpty() && parents.getLast().equals(".mvn")) {
			return true;
		}
		return OUTPUT_DIRS.contains(name) && !parents.contains("src");
	}

	/** Checked before the include list: these are never read, even if another rule would include them. */
	static boolean isSecretFile(String path) {
		String name = fileName(path);
		return SECRET_PREFIXES.stream().anyMatch(name::startsWith) || SECRET_SUFFIXES.stream().anyMatch(name::endsWith);
	}

	/** The include list: Java sources, build files, Spring config, SQL, docs. Everything else is ignored. */
	static boolean isIncluded(String path) {
		String name = fileName(path);
		return name.endsWith(".java")
				|| isBuildFile(path)
				|| isApplicationConfig(path)
				|| (name.endsWith(".sql") && (isMigration(path) || segments(path).contains("resources")))
				|| (name.endsWith(".md") && (name.startsWith("readme") || !path.contains("/")));
	}

	/** pom.xml, Gradle scripts, Dockerfiles, Compose files, GitHub Actions workflows. */
	static boolean isBuildFile(String path) {
		String name = fileName(path);
		return name.equals("pom.xml")
				|| GRADLE_SCRIPT.matcher(name).matches()
				|| name.equals("dockerfile") || name.startsWith("dockerfile.")
				|| COMPOSE_FILE.matcher(name).matches()
				|| WORKFLOW.matcher(lower(path)).matches();
	}

	static boolean isApplicationConfig(String path) {
		return APPLICATION_CONFIG.matcher(fileName(path)).matches();
	}

	/** A {@code .sql} file in a {@code db/migration} folder (Flyway's default location). */
	static boolean isMigration(String path) {
		return fileName(path).endsWith(".sql")
				&& Collections.indexOfSubList(segments(path), List.of("db", "migration")) >= 0;
	}

	/** Under {@code src/test/}, in a single-module or a multi-module build. */
	static boolean isTest(String path) {
		return Collections.indexOfSubList(segments(path), List.of("src", "test")) >= 0;
	}

	/** The value stored in {@code code_file.language}. */
	static String language(String path) {
		String name = fileName(path);
		if (name.equals("dockerfile") || name.startsWith("dockerfile.")) {
			return "dockerfile";
		}
		return switch (name.substring(name.lastIndexOf('.') + 1)) {
			case "java" -> JAVA;
			case "yml", "yaml" -> "yaml";
			case "properties" -> "properties";
			case "xml" -> "xml";
			case "sql" -> "sql";
			case "md" -> "markdown";
			case "gradle" -> "groovy";
			case "kts" -> "kotlin";
			default -> "text";
		};
	}

	/** Lowercase file name, without folders. */
	static String fileName(String path) {
		return lower(path.substring(path.lastIndexOf('/') + 1));
	}

	private static List<String> segments(String path) {
		return List.of(lower(path).split("/"));
	}

	private static String lower(String text) {
		return text.toLowerCase(Locale.ROOT);
	}

}
