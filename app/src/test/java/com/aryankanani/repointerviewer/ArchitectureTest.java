package com.aryankanani.repointerviewer;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import jakarta.persistence.Entity;
import java.util.List;

/**
 * Module boundaries from docs/LLD.md §2, checked on the compiled main classes (not tests).
 * Never weaken a rule to make code compile; fix the design instead.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE,
		importOptions = { ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestSupport.class })
class ArchitectureTest {

	static final String BASE = "com.aryankanani.repointerviewer";

	private static final List<String> MODULES_WITH_ENTITIES = List.of("common", "llm", "codebase", "project", "interview");

	/** One line per row of the LLD §2 dependency table. */
	@ArchTest
	static final ArchRule modulesDependOnlyInOneDirection = layeredArchitecture()
			.consideringOnlyDependenciesInLayers()
			// Most modules are still empty in Phase 0. An empty module must not fail the rule.
			.withOptionalLayers(true)
			.layer("api").definedBy(BASE + ".api..")
			.layer("interview").definedBy(BASE + ".interview..")
			.layer("project").definedBy(BASE + ".project..")
			.layer("codebase").definedBy(BASE + ".codebase..")
			.layer("llm").definedBy(BASE + ".llm..")
			.layer("contracts").definedBy(BASE + ".contracts..")
			.layer("common").definedBy(BASE + ".common..")
			// Task 4.1: api and interview reach project and codebase only through contracts.analysis.AnalysisApi, and
			// api reaches interview only through contracts.interview.InterviewApi (step 3a).
			.whereLayer("api").mayOnlyAccessLayers("contracts", "common")
			.whereLayer("api").mayNotBeAccessedByAnyLayer()
			.whereLayer("interview").mayOnlyAccessLayers("llm", "contracts", "common")
			.whereLayer("project").mayOnlyAccessLayers("codebase", "llm", "contracts", "common")
			.whereLayer("codebase").mayOnlyAccessLayers("contracts", "common")
			.whereLayer("llm").mayOnlyAccessLayers("contracts", "common")
			.whereLayer("contracts").mayNotAccessAnyLayer()
			.whereLayer("common").mayNotAccessAnyLayer();

	@ArchTest
	static final ArchRule onlyLlmUsesSpringAiChat = noClasses()
			.that().resideOutsideOfPackage(BASE + ".llm..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat..")
			.because("all chat-model calls go through llm.LlmGateway, which records every call in llm_call");

	@ArchTest
	static final ArchRule onlyCodebaseEmbedUsesSpringAiEmbedding = noClasses()
			.that().resideOutsideOfPackage(BASE + ".codebase.embed..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.ai.embedding..")
			.because("embeddings are created only by codebase.embed.EmbeddingService");

	@ArchTest
	static final ArchRule onlyApiUsesSpringGraphQl = noClasses()
			.that().resideOutsideOfPackage(BASE + ".api..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.graphql..")
			.because("GraphQL is a delivery detail of the api module");

	/** Other modules, api included since task 4.1, get records from their module's queries or API. */
	@ArchTest
	static final ArchRule entitiesAreUsedOnlyByTheirModule =
			CompositeArchRule.of(MODULES_WITH_ENTITIES.stream().map(ArchitectureTest::entitiesStayIn).toList());

	private static ArchRule entitiesStayIn(String module) {
		String modulePackage = BASE + "." + module + "..";
		return noClasses()
				.that().resideOutsideOfPackage(modulePackage)
				.should().dependOnClassesThat(resideInAPackage(modulePackage).and(annotatedWith(Entity.class)))
				.because("entities stay inside their module (docs/LLD.md §2)");
	}

	/** {@code libs/test-support} is test code in a jar (its fakes live in the base package): not checked here. */
	static final class DoNotIncludeTestSupport implements ImportOption {

		@Override
		public boolean includes(Location location) {
			return !location.contains("test-support");
		}

	}

}
