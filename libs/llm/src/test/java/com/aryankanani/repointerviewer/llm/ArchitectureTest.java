package com.aryankanani.repointerviewer.llm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * {@code libs/llm}'s rule (docs/LLD.md §2, task 4.1): it uses {@code common} and {@code contracts}, no other module.
 */
@AnalyzeClasses(packages = "com.aryankanani.repointerviewer.llm", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule usesOnlyCommonAndContracts = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("com.aryankanani.repointerviewer.outbox..",
					"com.aryankanani.repointerviewer.api..", "com.aryankanani.repointerviewer.project..",
					"com.aryankanani.repointerviewer.codebase..", "com.aryankanani.repointerviewer.interview..")
			.because("the services use llm, so llm uses none of them");

}
