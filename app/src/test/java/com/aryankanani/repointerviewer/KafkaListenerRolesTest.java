package com.aryankanani.repointerviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Every Kafka listener starts only in its role (task 3.3, LLD §14.3). The listeners are found in the compiled main
 * classes, so a new one without a role, or with the wrong one, fails here: it would run in every Deployment.
 */
class KafkaListenerRolesTest {

	private static final String ANALYSIS = "${app.roles.analysis-worker}";

	private static final String INTERVIEW = "${app.roles.interview-worker}";

	private static final Map<String, String> EXPECTED = Map.of(
			"IngestConsumer", ANALYSIS, "EmbedConsumer", ANALYSIS, "SummaryConsumer", ANALYSIS,
			"AnswerEvaluationConsumer", INTERVIEW, "InterviewOrchestrator", INTERVIEW,
			"InterviewEventsFanOut", "${app.roles.api}");

	@Test
	void everyKafkaListenerStartsOnlyInItsRole() {
		Map<String, String> found = new TreeMap<>();
		for (JavaClass type : new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.aryankanani.repointerviewer")) {
			for (JavaMethod method : type.getMethods()) {
				if (method.isAnnotatedWith(KafkaListener.class)) {
					found.put(type.getSimpleName(), method.reflect().getAnnotation(KafkaListener.class).autoStartup());
				}
			}
		}
		assertThat(found).isEqualTo(new TreeMap<>(EXPECTED));
	}

}
