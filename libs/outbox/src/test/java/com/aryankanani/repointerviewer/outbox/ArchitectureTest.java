package com.aryankanani.repointerviewer.outbox;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** {@code libs/outbox}'s rule (docs/LLD.md §2, task 4.1): it uses {@code common}, no other module. */
@AnalyzeClasses(packages = "com.aryankanani.repointerviewer.outbox",
		importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule usesOnlyCommon = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("com.aryankanani.repointerviewer.contracts..",
					"com.aryankanani.repointerviewer.llm..", "com.aryankanani.repointerviewer.api..",
					"com.aryankanani.repointerviewer.project..", "com.aryankanani.repointerviewer.codebase..",
					"com.aryankanani.repointerviewer.interview..")
			.because("the services use the outbox, so it uses none of them");

}
