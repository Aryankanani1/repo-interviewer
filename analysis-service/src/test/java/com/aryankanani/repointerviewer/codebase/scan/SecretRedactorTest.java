package com.aryankanani.repointerviewer.codebase.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Token-shaped values are glued together at runtime ({@code "sk-" + "ant-" + ...}), so this source file never
 * contains a string that GitHub push protection or another secret scanner would take for a real secret.
 */
class SecretRedactorTest {

	private static final String AWS_KEY = "AKIA" + "IOSFODNN7EXAMPLE";   // the example key from AWS's docs

	private static final String GITHUB_CLASSIC = "ghp" + "_" + "a1B2".repeat(9);

	private static final String GITHUB_FINE_GRAINED = "github" + "_pat_" + "11ABCDEFG0" + "x".repeat(40);

	private static final String ANTHROPIC = "sk-" + "ant-" + "api03-" + "Zz9".repeat(15);

	private static final String OPENAI_STYLE = "sk-" + "proj-" + "Yy8".repeat(15);

	private static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9" + ".eyJ" + "zdWIiOiIxMjM0In0" + ".dBjftJeZ4CVP_mB92K27";

	private final SecretRedactor redactor = new SecretRedactor();

	static Stream<Arguments> tokens() {
		return Stream.of(
				arguments("aws_access_key", AWS_KEY),
				arguments("github_token", GITHUB_CLASSIC),
				arguments("github_token", GITHUB_FINE_GRAINED),
				arguments("anthropic_key", ANTHROPIC),
				arguments("api_key", OPENAI_STYLE),
				arguments("jwt", JWT));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("tokens")
	void redactsTokenFormatsInAnyFile(String type, String secret) {
		RedactedFile file = redact("src/main/java/com/acme/Keys.java",
				"class Keys {\n    String value = \"" + secret + "\";\n}\n");

		assertThat(file.content()).isEqualTo("class Keys {\n    String value = \"<REDACTED:" + type + ">\";\n}\n");
		assertThat(file.redactionCount()).isEqualTo(1);
	}

	@Test
	void privateKeyBlockBecomesOneMarkerAndKeepsTheLineCount() {
		String key = "-----BEGIN " + "RSA PRIVATE KEY-----\n" + "MIIEowIBAAKCAQEAx4Lm\n".repeat(3) + "-----END "
				+ "RSA PRIVATE KEY-----";
		String content = "# Deploy notes\n\n" + key + "\n\nRotate it every 90 days.\n";

		RedactedFile file = redact("README.md", content);

		assertThat(file.content()).contains("<REDACTED:private_key>").doesNotContain("MIIE").doesNotContain("BEGIN");
		assertThat(lines(file.content())).hasSameSizeAs(lines(content));
		// A line after the key is still on the same line number.
		assertThat(lines(file.content()).indexOf("Rotate it every 90 days."))
				.isEqualTo(lines(content).indexOf("Rotate it every 90 days."));
		assertThat(file.redactionCount()).isEqualTo(1);
	}

	@Test
	void privateKeyWithoutAnEndLineIsRedactedToTheEndOfTheFile() {
		String content = "Key:\n-----BEGIN " + "PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nAQEAx4Lm\n";

		RedactedFile file = redact("README.md", content);

		assertThat(file.content()).doesNotContain("MIIE").doesNotContain("AQEAx4Lm");
		assertThat(lines(file.content())).hasSameSizeAs(lines(content));
	}

	@Test
	void fixturePasswordIsRedactedAndItsPlaceholdersAreKept() throws IOException {
		String path = "src/main/resources/application.yml";
		String original = Files.readString(LocalDirectoryRepoFetcher.fixtureRoot().resolve(path));

		RedactedFile file = redact(path, original);

		assertThat(file.content())
				.contains("    password: <REDACTED:password>")
				.doesNotContain("fixture-pa55word-not-real")
				.contains("    username: ${DB_USERNAME:shop}")
				.contains("    api-key: ${PAYMENT_API_KEY}");
		assertThat(file.redactionCount()).isEqualTo(1);
		assertThat(lines(file.content())).hasSameSizeAs(lines(original));
	}

	static Stream<Arguments> secretAssignments() {
		return Stream.of(
				arguments("password: hunter2", "password: <REDACTED:password>"),
				arguments("  client-secret: abc123", "  client-secret: <REDACTED:secret>"),
				arguments("github.token: abc123", "github.token: <REDACTED:token>"),
				arguments("stripe.api-key: abc123", "stripe.api-key: <REDACTED:api_key>"),
				arguments("password: \"hunter2\"", "password: \"<REDACTED:password>\""),
				// The default of a placeholder is a hard-coded value too.
				arguments("password: ${DB_PASSWORD:hunter2}", "password: ${DB_PASSWORD:<REDACTED:password>}"),
				// Docker Compose environment, as a list and as a map.
				arguments("      - POSTGRES_PASSWORD=hunter2", "      - POSTGRES_PASSWORD=<REDACTED:password>"),
				arguments("      - \"POSTGRES_PASSWORD=hunter2\"", "      - \"POSTGRES_PASSWORD=<REDACTED:password>\""),
				arguments("      POSTGRES_PASSWORD: hunter2", "      POSTGRES_PASSWORD: <REDACTED:password>"),
				// Commented out, but still committed.
				arguments("# password: hunter2", "# password: <REDACTED:password>"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("secretAssignments")
	void redactsSecretAssignmentsInYaml(String line, String expected) {
		RedactedFile file = redact("src/main/resources/application.yml", line + "\n");

		assertThat(file.content()).isEqualTo(expected + "\n");
		assertThat(file.redactionCount()).isEqualTo(1);
	}

	@Test
	void redactsSecretAssignmentsInProperties() {
		RedactedFile file = redact("src/main/resources/application.properties",
				"spring.datasource.password=hunter2\napp.jwt.secret = abc123\nserver.port=8080\n");

		assertThat(file.content()).isEqualTo(
				"spring.datasource.password=<REDACTED:password>\napp.jwt.secret = <REDACTED:secret>\nserver.port=8080\n");
	}

	/** False positives: config lines whose key looks secret but whose value cannot be one. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("harmlessConfigLines")
	void keepsValuesThatCannotBeSecrets(String line) {
		RedactedFile file = redact("src/main/resources/application.yml", line + "\n");

		assertThat(file.content()).isEqualTo(line + "\n");
		assertThat(file.redactionCount()).isZero();
	}

	static Stream<String> harmlessConfigLines() {
		return Stream.of(
				"api-key: ${PAYMENT_API_KEY}",
				"password: ${DB_PASSWORD}",
				"password: ${DB_PASSWORD:}",
				"spring.ai.anthropic.chat.options.max-tokens: 1024",
				"token-expiry: 15m",
				"secret-scanning: true",
				"password:",
				"password: \"\"",
				"password-min-length: 8",
				"username: shop");
	}

	@Test
	void assignmentRuleOnlyAppliesToConfigFilesAndProseIsLeftAlone() {
		String java = "class Db {\n    String password = \"hunter2\";\n}\n";
		String prose = "A risk-free task-management tool. Ask for an sk-short code.\n";

		assertThat(redact("src/main/java/com/acme/Db.java", java).content()).isEqualTo(java);
		assertThat(redact("README.md", prose).content()).isEqualTo(prose);
	}

	@Test
	void tokenInsideAnAssignmentIsRedactedOnce() {
		RedactedFile file = redact("src/main/resources/application.yml", "github.token: " + GITHUB_CLASSIC + "\n");

		assertThat(file.content()).isEqualTo("github.token: <REDACTED:github_token>\n");
		assertThat(file.redactionCount()).isEqualTo(1);
	}

	private RedactedFile redact(String path, String content) {
		return redactor.redact(new ScannedFile(path, FilePatterns.language(path), content,
				(int) content.lines().count(), content.length(), "unused"));
	}

	private static List<String> lines(String text) {
		return text.lines().toList();
	}

}
