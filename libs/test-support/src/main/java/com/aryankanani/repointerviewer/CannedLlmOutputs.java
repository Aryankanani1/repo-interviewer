package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import com.aryankanani.repointerviewer.llm.output.LlmOutput;
import com.aryankanani.repointerviewer.llm.output.ModuleOutput;
import com.aryankanani.repointerviewer.llm.output.PlanOutput;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.aryankanani.repointerviewer.llm.output.ReportOutput;
import com.aryankanani.repointerviewer.llm.output.ReviewTopicOutput;
import com.aryankanani.repointerviewer.llm.output.RubricOutput;
import com.aryankanani.repointerviewer.llm.output.SummaryOutput;
import java.util.List;

/**
 * One plausible model answer per {@link LlmPurpose}, about the fixture repository: what {@link FakeLlmGateway}
 * returns in a test's JVM and what {@code FakeModelServer} answers over HTTP to a real app process (task 4.1), so
 * the two fakes cannot drift apart.
 */
public final class CannedLlmOutputs {

	private CannedLlmOutputs() {
	}

	public static LlmOutput<?> of(LlmPurpose purpose) {
		return switch (purpose) {
			case SUMMARY -> new SummaryOutput("A small order service that places and refunds orders.",
					"layered monolith", List.of("Spring Boot", "Spring Data JPA", "PostgreSQL", "Flyway"),
					List.of(new ModuleOutput("order", "Orders and refunds", List.of("OrderService", "Order"))),
					List.of("Refund limit checked in OrderService#refund"),
					List.of("Concurrent refunds on the same order in OrderService#refund"));
			case PLAN -> new PlanOutput(List.of(
					topic("Overall design", QuestionCategory.ARCHITECTURE, "OrderController.java", "OrderController"),
					topic("Refund rules", QuestionCategory.FAILURE_HANDLING, "OrderService.java", "OrderService#refund"),
					topic("Order entity", QuestionCategory.DATA_MODEL, "Order.java", "Order")));
			case QUESTION, FOLLOW_UP -> new QuestionOutput(
					"OrderService#refund rejects a refund larger than what is left. What happens if two refunds for "
							+ "the same order arrive at the same time?",
					List.of(new CodeRefOutput("src/main/java/com/example/shop/order/OrderService.java", 27, 42)),
					new RubricOutput(List.of("Both can read the same refundedAmount", "Lost update"),
							List.of("Optimistic locking with @Version"), List.of("Says @Transactional alone prevents it")),
					List.of("Both transactions read the same state", "The check passes twice",
							"Use @Version or SELECT ... FOR UPDATE"));
			case EVALUATION -> new EvaluationOutput(6, List.of("Lost update"), List.of("Optimistic locking"),
					List.of(), "You spotted the race but not how to prevent it.", true,
					"Check whether the candidate knows a fix.");
			case REPORT -> new ReportOutput("You know the refund flow well but not its concurrency limits.",
					List.of("Clear explanation of the refund check"), List.of("Locking strategies"),
					List.of(new ReviewTopicOutput("Optimistic locking in the refund flow",
							"Two refunds can pass the same check.")));
		};
	}

	private static PlannedTopic topic(String title, QuestionCategory category, String file, String symbol) {
		return new PlannedTopic(title, category, List.of("src/main/java/com/example/shop/order/" + file),
				List.of(symbol), "Shows whether the candidate understands " + symbol + ".");
	}

}
