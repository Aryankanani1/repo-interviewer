package com.aryankanani.repointerviewer.interview.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Task 1.10: plan topics must point at code that exists (LLD §9.1). Names and paths are the fixture's. */
class TopicValidatorTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private static final Set<String> PATHS = Set.of(ORDER + "Order.java", ORDER + "OrderController.java",
			ORDER + "OrderService.java", "src/main/resources/application.yml");

	private static final Set<String> SYMBOLS = Set.of("Order", "Order.Status", "Order#Order(String,BigDecimal)",
			"Order#markRefunded", "Order#small-methods", "OrderController", "OrderController#get",
			"OrderController#refund", "OrderService", "OrderService#refund", "OrderService#find");

	private final TopicValidator validator = new TopicValidator();

	@Test
	void topicsAboutInventedCodeAreDropped() {
		List<PlannedTopic> kept = validator.validate(List.of(
				topic("Payments", List.of("src/main/java/com/example/shop/payment/PaymentService.java"),
						List.of("PaymentService#charge")),
				topic("Refunds", List.of(ORDER + "OrderService.java"), List.of("OrderService#refund"))), PATHS, SYMBOLS, 5);

		assertThat(kept).extracting(PlannedTopic::title).containsExactly("Refunds");
	}

	@Test
	void onlyTheRealPathsAndSymbolsOfATopicAreKept() {
		List<PlannedTopic> kept = validator.validate(List.of(topic("Refunds",
				List.of("./" + ORDER + "OrderService.java", ORDER + "RefundPolicy.java"),
				List.of("OrderService#refund", "RefundPolicy"))), PATHS, SYMBOLS, 5);

		assertThat(kept).singleElement().satisfies(topic -> {
			assertThat(topic.targetPaths()).containsExactly(ORDER + "OrderService.java");   // "./" removed
			assertThat(topic.targetSymbols()).containsExactly("OrderService#refund");
		});
	}

	@Test
	void symbolsMatchOverloadsAndAnUnknownMethodFallsBackToItsClass() {
		assertThat(TopicValidator.resolve("Order#Order", SYMBOLS)).isEqualTo("Order#Order");   // overload
		assertThat(TopicValidator.resolve("Order.Status", SYMBOLS)).isEqualTo("Order.Status");
		assertThat(TopicValidator.resolve("OrderController#create", SYMBOLS)).isEqualTo("OrderController");
		assertThat(TopicValidator.resolve("PaymentService#charge", SYMBOLS)).isNull();
		assertThat(TopicValidator.resolve("orderservice", SYMBOLS)).isNull();
	}

	@Test
	void aSecondTopicOnTheSameClassIsDropped() {
		List<PlannedTopic> kept = validator.validate(List.of(
				topic("Refund rules", List.of(), List.of("OrderService#refund")),
				topic("Service design", List.of(ORDER + "OrderService.java"), List.of()),
				topic("Order entity", List.of(), List.of("Order"))), PATHS, SYMBOLS, 5);

		assertThat(kept).extracting(PlannedTopic::title).containsExactly("Refund rules", "Order entity");
	}

	@Test
	void topicsWithoutCategoryOrTitleAreDroppedAndTheCountIsCapped() {
		List<PlannedTopic> kept = validator.validate(List.of(
				new PlannedTopic("No category", null, List.of(), List.of("Order"), "r"),
				new PlannedTopic("", QuestionCategory.DATA_MODEL, List.of(), List.of("Order"), "r"),
				topic("A", List.of(), List.of("Order")),
				topic("B", List.of(), List.of("OrderController")),
				topic("C", List.of(), List.of("OrderService"))), PATHS, SYMBOLS, 2);

		assertThat(kept).extracting(PlannedTopic::title).containsExactly("A", "B");
	}

	@Test
	void aTopicWithOnlyAPathOfANonJavaFileIsKept() {
		List<PlannedTopic> kept = validator.validate(
				List.of(topic("Config", List.of("src/main/resources/application.yml"), List.of())), PATHS, SYMBOLS, 5);

		assertThat(kept).singleElement()
				.satisfies(topic -> assertThat(topic.targetPaths()).containsExactly("src/main/resources/application.yml"));
	}

	private static PlannedTopic topic(String title, List<String> paths, List<String> symbols) {
		return new PlannedTopic(title, QuestionCategory.DESIGN_DECISION, paths, symbols, "Why it matters.");
	}

}
