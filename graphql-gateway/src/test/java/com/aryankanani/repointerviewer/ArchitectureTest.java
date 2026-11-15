package com.aryankanani.repointerviewer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * graphql-gateway's boundaries (docs/LLD.md §2, task 4.1), checked on the compiled main classes of this app and its
 * libraries (not tests). Never weaken a rule to make code compile; fix the design instead. Maven Enforcer keeps the
 * services' modules off the classpath; these rules also keep their packages out of the code.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE,
		importOptions = { ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestSupport.class })
class ArchitectureTest {

	static final String BASE = "com.aryankanani.repointerviewer";

	/** One line per row of the LLD §2 dependency table. */
	@ArchTest
	static final ArchRule modulesDependOnlyInOneDirection = layeredArchitecture()
			.consideringOnlyDependenciesInLayers()
			// Most modules are still empty in Phase 0. An empty module must not fail the rule.
			.withOptionalLayers(true)
			.layer("api").definedBy(BASE + ".api..")
			.layer("contracts").definedBy(BASE + ".contracts..")
			.layer("common").definedBy(BASE + ".common..")
			// Task 4.1: the other modules are services of their own; api reaches them only through
			// contracts.analysis.AnalysisApi and contracts.interview.InterviewApi, over HTTP.
			.whereLayer("api").mayOnlyAccessLayers("contracts", "common")
			.whereLayer("api").mayNotBeAccessedByAnyLayer()
			.whereLayer("contracts").mayNotAccessAnyLayer()
			.whereLayer("common").mayNotAccessAnyLayer();

	@ArchTest
	static final ArchRule noModelCallsHere = noClasses()
			.should().dependOnClassesThat().resideInAPackage("org.springframework.ai..")
			.because("chat and embedding models are the services' since task 4.1, each call in their llm_call");

	@ArchTest
	static final ArchRule onlyApiUsesSpringGraphQl = noClasses()
			.that().resideOutsideOfPackage(BASE + ".api..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.graphql..")
			.because("GraphQL is a delivery detail of the api module");

	@ArchTest
	static final ArchRule usesNoOtherServicesCode = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage(BASE + ".project..", BASE + ".codebase..",
					BASE + ".interview..", BASE + ".llm..", BASE + ".outbox..")
			.because("the services are reached only through contracts over HTTP, and Kafka (DECISIONS.md D-026)");

	@ArchTest
	static final ArchRule usesNoDatabase = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.jdbc..",
					"org.springframework.transaction..")
			.because("the gateway has no database since task 4.1 step 4a: its data is the services'");

	/** {@code libs/test-support} is test code in a jar (its fakes live in the base package): not checked here. */
	static final class DoNotIncludeTestSupport implements ImportOption {

		@Override
		public boolean includes(Location location) {
			return !location.contains("test-support");
		}

	}

}
