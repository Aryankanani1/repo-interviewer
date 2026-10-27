package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test {@link LlmGateway} (DECISIONS.md D-012): no network, no cost. Returns a canned output per purpose, about
 * the fixture repository, unless a test queued something else: a specific output (also an invalid one, like a
 * plan with invented paths) or a failure. Outputs are normalized like the real gateway does. Every request is
 * recorded, so tests can check how many calls were made.
 *
 * <p>Shared by all integration tests ({@code @Primary} in {@link TestDoublesConfiguration}): call {@link #reset}
 * in {@code @BeforeEach}.
 */
public class FakeLlmGateway implements LlmGateway {

	private final Map<LlmPurpose, Deque<Object>> queued = new EnumMap<>(LlmPurpose.class);

	private final List<LlmRequest<?>> requests = new CopyOnWriteArrayList<>();

	public synchronized void reset() {
		queued.clear();
		requests.clear();
	}

	/** The next call for this purpose returns {@code output}; later calls fall back to the canned one. */
	public synchronized void enqueue(LlmPurpose purpose, LlmOutput<?> output) {
		queued.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(output);
	}

	/** The next call for this purpose throws {@code failure}, for example an {@code LlmUnavailableException}. */
	public synchronized void enqueueFailure(LlmPurpose purpose, RuntimeException failure) {
		queued.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(failure);
	}

	public List<LlmRequest<?>> requests() {
		return List.copyOf(requests);
	}

	public long calls(LlmPurpose purpose) {
		return requests.stream().filter(request -> request.purpose() == purpose).count();
	}

	@Override
	public <T extends LlmOutput<T>> T call(LlmRequest<T> request) {
		requests.add(request);
		Object next = next(request.purpose());
		if (next instanceof RuntimeException failure) {
			throw failure;
		}
		return request.outputType().cast(next).normalized();
	}

	@Override
	public String promptVersion(LlmPurpose purpose) {
		return purpose.template() + "@fake0000";
	}

	private synchronized Object next(LlmPurpose purpose) {
		Deque<Object> queue = queued.get(purpose);
		return queue == null || queue.isEmpty() ? canned(purpose) : queue.poll();
	}

	private static LlmOutput<?> canned(LlmPurpose purpose) {
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
