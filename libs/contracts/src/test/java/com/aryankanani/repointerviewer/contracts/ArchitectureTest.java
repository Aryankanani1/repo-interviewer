package com.aryankanani.repointerviewer.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * {@code libs/contracts}' rule (docs/LLD.md §2, task 4.1): the API between the services is plain Java records and
 * the {@code @HttpExchange} annotations, nothing else, so every service can share it without sharing anything more.
 */
@AnalyzeClasses(packages = "com.aryankanani.repointerviewer.contracts",
		importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule onlyJavaAndTheHttpAnnotations = classes()
			.should().onlyDependOnClassesThat().resideInAnyPackage("java..",
					"org.springframework.web.bind.annotation..", "org.springframework.web.service.annotation..",
					"com.aryankanani.repointerviewer.contracts..")
			.because("the contracts are what the services share, so they depend on no module and no framework");

}
