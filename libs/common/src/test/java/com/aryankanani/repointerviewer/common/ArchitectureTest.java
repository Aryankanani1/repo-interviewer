package com.aryankanani.repointerviewer.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * {@code libs/common}'s rules (docs/LLD.md §2, task 4.1): the shared kernel uses none of this system's other modules,
 * and no database, so the GraphQL gateway can use it without one. Checked on its compiled main classes.
 */
@AnalyzeClasses(packages = ArchitectureTest.COMMON, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	static final String COMMON = "com.aryankanani.repointerviewer.common";

	@ArchTest
	static final ArchRule usesNoOtherModule = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("com.aryankanani.repointerviewer.contracts..",
					"com.aryankanani.repointerviewer.llm..", "com.aryankanani.repointerviewer.outbox..",
					"com.aryankanani.repointerviewer.api..", "com.aryankanani.repointerviewer.project..",
					"com.aryankanani.repointerviewer.codebase..", "com.aryankanani.repointerviewer.interview..")
			.because("every other module may depend on common, so common depends on none of them");

	@ArchTest
	static final ArchRule usesNoDatabase = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.data..",
					"org.springframework.jdbc..", "org.springframework.transaction..")
			.because("the GraphQL gateway has no database (task 4.1): database code goes in libs/outbox or a service");

}
