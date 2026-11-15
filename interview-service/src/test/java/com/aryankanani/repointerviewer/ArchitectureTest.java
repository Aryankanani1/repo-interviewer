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
 * interview-service's module boundaries (docs/LLD.md §2, task 4.1), checked on the compiled main classes of this
 * service and its libraries (not tests). Never weaken a rule to make code compile; fix the design instead.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE,
		importOptions = { ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestSupport.class })
class ArchitectureTest {

	static final String BASE = "com.aryankanani.repointerviewer";

	private static final List<String> MODULES_WITH_ENTITIES = List.of("outbox", "llm", "interview");

	@ArchTest
	static final ArchRule modulesDependOnlyInOneDirection = layeredArchitecture()
			.consideringOnlyDependenciesInLayers()
			.layer("interview").definedBy(BASE + ".interview..")
			.layer("llm").definedBy(BASE + ".llm..")
			.layer("outbox").definedBy(BASE + ".outbox..")
			.layer("contracts").definedBy(BASE + ".contracts..")
			.layer("common").definedBy(BASE + ".common..")
			.whereLayer("interview").mayNotBeAccessedByAnyLayer()
			// Projects and code come from analysis-service, only through contracts.analysis.AnalysisApi.
			.whereLayer("interview").mayOnlyAccessLayers("llm", "outbox", "contracts", "common")
			.whereLayer("llm").mayOnlyAccessLayers("contracts", "common")
			.whereLayer("outbox").mayOnlyAccessLayers("common")
			.whereLayer("contracts").mayNotAccessAnyLayer()
			.whereLayer("common").mayNotAccessAnyLayer();

	/** A second fence behind Maven Enforcer (task 4.1): the other services' packages stay out of this code too. */
	@ArchTest
	static final ArchRule usesNoOtherServicesCode = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage(BASE + ".project..", BASE + ".codebase..",
					BASE + ".api..")
			.because("the services are reached only through contracts over HTTP, and Kafka (DECISIONS.md D-026)");

	@ArchTest
	static final ArchRule onlyLlmUsesSpringAiChat = noClasses()
			.that().resideOutsideOfPackage(BASE + ".llm..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat..")
			.because("all chat-model calls go through llm.LlmGateway, which records every call in llm_call");

	@ArchTest
	static final ArchRule noEmbeddingsHere = noClasses()
			.should().dependOnClassesThat().resideInAPackage("org.springframework.ai.embedding..")
			.because("embeddings are analysis-service's: a code search goes through AnalysisApi");

	/** No GraphQL here: an entity never leaves its module; others get records ({@code InterviewApi}'s, or queries'). */
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
