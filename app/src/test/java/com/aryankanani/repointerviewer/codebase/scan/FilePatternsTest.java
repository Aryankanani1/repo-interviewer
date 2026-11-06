package com.aryankanani.repointerviewer.codebase.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FilePatternsTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"src/main/java/com/acme/Shop.java",
			"pom.xml",
			"module-a/pom.xml",
			"build.gradle",
			"settings.gradle.kts",
			"src/main/resources/application.yml",
			"src/main/resources/application-prod.properties",
			"config/application.yaml",
			"Dockerfile",
			"docker/Dockerfile.dev",
			"docker-compose.yml",
			"compose.yaml",
			"compose.override.yml",
			"src/main/resources/db/migration/V1__init.sql",
			"src/main/resources/schema.sql",
			"README.md",
			"docs/readme.md",
			"CONTRIBUTING.md",
			".github/workflows/ci.yml",
			".github/workflows/release.yaml" })
	void includesSourceBuildConfigSqlAndDocs(String path) {
		assertThat(FilePatterns.isIncluded(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"docs/guide.md",                    // Markdown below the top level that is not a README
			"scripts/seed.sql",                 // SQL outside resources/ and db/migration/
			"src/main/resources/logback.xml",
			"src/main/resources/messages.properties",
			"frontend/package.json",
			"notes.txt",
			"logo.png",
			".github/workflows/nested/ci.yml",
			"composer.yml" })
	void ignoresEverythingElse(String path) {
		assertThat(FilePatterns.isIncluded(path)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { ".env", ".env.local", "config/.env.production", "id_rsa", "id_ed25519",
			"certs/server.pem", "tls.key", "keystore.p12", "app.jks", "release.keystore", "credentials.json" })
	void recognizesSecretFiles(String path) {
		assertThat(FilePatterns.isSecretFile(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "src/main/resources/application.yml", "src/main/java/com/acme/ApiKey.java",
			"src/main/java/com/acme/TokenService.java", "docs/environment.md" })
	void doesNotMistakeOrdinaryFilesForSecrets(String path) {
		assertThat(FilePatterns.isSecretFile(path)).isFalse();
	}

	@ParameterizedTest(name = "{0} ignored: {1}")
	@CsvSource({
			"target,                                true",
			"module-a/target,                       true",
			"build,                                 true",
			"frontend/dist,                         true",
			"node_modules,                          true",
			"web/node_modules,                      true",
			".git,                                  true",
			".idea,                                 true",
			".mvn/wrapper,                          true",
			// Inside src/ these names are Java packages, not build output.
			"src/main/java/com/acme/vendor,         false",
			"src/main/java/com/acme/build,          false",
			"module-a/src/main/java/com/acme/out,   false",
			".mvn,                                  false",
			".github,                               false",
			"wrapper,                               false" })
	void ignoresBuildOutputOnlyOutsideSrc(String dirPath, boolean ignored) {
		assertThat(FilePatterns.isIgnoredDirectory(dirPath)).isEqualTo(ignored);
	}

	@ParameterizedTest(name = "{0} is {1}")
	@CsvSource({
			"src/main/java/com/acme/Shop.java,  java",
			"application.yml,                   yaml",
			"compose.yaml,                      yaml",
			"application.properties,            properties",
			"pom.xml,                           xml",
			"V1__init.sql,                      sql",
			"README.md,                         markdown",
			"build.gradle,                      groovy",
			"build.gradle.kts,                  kotlin",
			"Dockerfile,                        dockerfile",
			"docker/Dockerfile.dev,             dockerfile" })
	void detectsLanguage(String path, String language) {
		assertThat(FilePatterns.language(path)).isEqualTo(language);
	}

}
