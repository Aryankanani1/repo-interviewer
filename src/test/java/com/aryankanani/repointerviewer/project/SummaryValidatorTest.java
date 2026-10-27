package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.llm.output.ModuleOutput;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Task 1.9: the summary only names code that exists (HLD §10.2). */
class SummaryValidatorTest {

	private static final Set<String> KNOWN = Set.of("Order", "Order.Status", "Status", "OrderService",
			"OrderService.java", "OrderController", "application.yml", "pom.xml");

	private final SummaryValidator validator = new SummaryValidator();

	@Test
	void keyClassesThatDoNotExistAreDropped() {
		RepoSummary summary = validator.validate(summary(
				List.of(new ModuleOutput("order", "Orders", List.of("OrderService", "PaymentGateway", "Order")))),
				KNOWN);

		assertThat(summary.modules()).singleElement()
				.satisfies(module -> assertThat(module.keyClasses()).containsExactly("OrderService", "Order"));
	}

	@Test
	void itemsThatNameNoRealCodeAreDropped() {
		RepoSummary summary = validator.validate(new SummaryOutput("Shop", "layered", List.of("Spring Boot"),
				List.of(), List.of(
						"Refund limit checked in OrderService#refund().",
						"OrderRepository extends JpaRepository, but OrderController calls the service",
						"Circuit breaker in PaymentClient",
						"Retries everywhere"),
				List.of("Concurrent refunds on Order.Status", "Secrets in application.yml", "No tests for KafkaConsumer")),
				KNOWN);

		assertThat(summary.notablePatterns()).containsExactly(
				"Refund limit checked in OrderService#refund().",
				"OrderRepository extends JpaRepository, but OrderController calls the service");
		assertThat(summary.riskAreas()).containsExactly("Concurrent refunds on Order.Status",
				"Secrets in application.yml");
	}

	@Test
	void namesAreMatchedExactly() {
		assertThat(SummaryValidator.isKnown("OrderService,", KNOWN)).isTrue();
		assertThat(SummaryValidator.isKnown("OrderService#refund", KNOWN)).isTrue();
		assertThat(SummaryValidator.isKnown("orderservice", KNOWN)).isFalse();
		assertThat(SummaryValidator.isKnown("OrderServiceImpl", KNOWN)).isFalse();
		assertThat(SummaryValidator.mentionsKnown("the order service", KNOWN)).isFalse();
	}

	@Test
	void freeTextFieldsAreKept() {
		RepoSummary summary = validator.validate(summary(List.of()), KNOWN);

		assertThat(summary.oneLiner()).isEqualTo("Shop");
		assertThat(summary.architectureStyle()).isEqualTo("layered");
		assertThat(summary.techStack()).containsExactly("Spring Boot");
	}

	private static SummaryOutput summary(List<ModuleOutput> modules) {
		return new SummaryOutput("Shop", "layered", List.of("Spring Boot"), modules, List.of(), List.of());
	}

}
