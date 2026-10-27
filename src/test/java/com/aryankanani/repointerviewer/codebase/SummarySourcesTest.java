package com.aryankanani.repointerviewer.codebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.codebase.chunk.TestFiles;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Task 1.9: the build dependencies and README excerpt for the summary prompt. */
class SummarySourcesTest {

	@Test
	void fixturePomGivesItsParentAndDependencies() {
		String buildFiles = SummarySources.buildFiles(List.of(TestFiles.fixture("pom.xml")));

		assertThat(buildFiles).startsWith("pom.xml\n- parent org.springframework.boot:spring-boot-starter-parent:")
				.contains("\n- org.springframework.boot:spring-boot-starter-webmvc\n")
				.contains("\n- org.flywaydb:flyway-database-postgresql")
				.doesNotContain("<", "com.example:shop");   // the project's own coordinates are not a dependency
	}

	@Test
	void mavenTestScopeIsMarked() {
		assertThat(SummarySources.maven("""
				<project><dependencies>
				  <dependency><groupId>org.junit</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
				  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
				  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
				</dependencies></project>
				""")).containsExactly("org.junit:junit-jupiter (test)", "org.postgresql:postgresql");
	}

	@Test
	void gradleDependenciesAreReadFromBothScriptStyles() {
		assertThat(SummarySources.gradle("""
				dependencies {
				    implementation 'org.springframework.boot:spring-boot-starter-web'
				    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.2"))
				    runtimeOnly("org.postgresql:postgresql")
				    testImplementation 'org.springframework.boot:spring-boot-starter-test'
				}
				""")).containsExactly("org.springframework.boot:spring-boot-starter-web",
						"org.springframework.ai:spring-ai-bom:2.0.2", "org.postgresql:postgresql",
						"org.springframework.boot:spring-boot-starter-test (test)");
	}

	@Test
	void noBuildFileMeansNull() {
		assertThat(SummarySources.buildFiles(List.of(TestFiles.fixture("README.md")))).isNull();
	}

	@Test
	void theReadmeClosestToTheRootIsCutAt150Lines() {
		String longReadme = IntStream.rangeClosed(1, 200).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
		List<RedactedFile> files = List.of(
				TestFiles.text("docs/README.md", "# Docs"),
				TestFiles.text("Readme.md", longReadme),
				TestFiles.text("pom.xml", "<project/>"));

		String readme = SummarySources.readme(files);

		assertThat(readme.lines()).hasSize(SummarySources.README_LINES).first().isEqualTo("line 1");
		assertThat(readme).endsWith("line 150");
	}

	@Test
	void noReadmeMeansNull() {
		assertThat(SummarySources.readme(List.of(TestFiles.fixture("pom.xml")))).isNull();
	}

}
