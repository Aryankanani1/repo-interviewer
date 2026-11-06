package com.aryankanani.repointerviewer;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
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
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
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
			.layer("common").definedBy(BASE + ".common..")
			.whereLayer("api").mayOnlyAccessLayers("project", "interview", "codebase", "common")
			.whereLayer("api").mayNotBeAccessedByAnyLayer()
			.whereLayer("interview").mayOnlyAccessLayers("codebase", "llm", "project", "common")
			.whereLayer("project").mayOnlyAccessLayers("codebase", "llm", "common")
			.whereLayer("codebase").mayOnlyAccessLayers("common")
			.whereLayer("llm").mayOnlyAccessLayers("common")
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

	/** Other modules get records from queries; only api maps entities to GraphQL views. */
	@ArchTest
	static final ArchRule entitiesAreUsedOnlyByTheirModuleAndApi =
			CompositeArchRule.of(MODULES_WITH_ENTITIES.stream().map(ArchitectureTest::entitiesStayIn).toList());

	private static ArchRule entitiesStayIn(String module) {
		String modulePackage = BASE + "." + module + "..";
		return noClasses()
				.that().resideOutsideOfPackages(modulePackage, BASE + ".api..")
				.should().dependOnClassesThat(resideInAPackage(modulePackage).and(annotatedWith(Entity.class)))
				.because("entities stay inside their module (docs/LLD.md §2)");
	}

}
