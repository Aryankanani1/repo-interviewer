package com.aryankanani.repointerviewer.codebase.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeEmbeddingModel;
import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.codebase.store.RetrievedChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task 1.10: what code a question gets (LLD §7), on the analyzed fixture. */
@IntegrationTest
class RetrieverTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private final Retriever retriever;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	private final FakeEmbeddingModel embeddingModel;

	private UUID project;

	RetrieverTest(@Autowired Retriever retriever, @Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc,
			@Autowired FakeEmbeddingModel embeddingModel) {
		this.retriever = retriever;
		this.fixture = fixture;
		this.jdbc = jdbc;
		this.embeddingModel = embeddingModel;
	}

	@BeforeEach
	void readyProject() {
		jdbc.update("DELETE FROM project");
		embeddingModel.reset();
		project = fixture.readyProject();
	}

	@Test
	void aNamedMethodComesFirstThenItsClassHeaderThenTheRestOfTheFile() {
		RetrievedContext context = retriever.forTopic(project,
				query("Refund rules", List.of(ORDER + "OrderService.java"), List.of("OrderService#refund")), 6000);

		assertThat(symbols(context).subList(0, 5)).containsExactly("OrderService#refund", "OrderService",
				"OrderService#small-methods", "OrderService#find", "OrderService#place");
		assertThat(context.chunks()).extracting(RetrievedChunk::id).doesNotHaveDuplicates();
		assertThat(context.chunks().size()).isGreaterThan(5);   // search by meaning added more after them
	}

	@Test
	void aNamedClassBringsItsHeaderThenItsMethodsInFileOrder() {
		RetrievedContext context = retriever.forTopic(project, query("The order entity", List.of(), List.of("Order")),
				6000);

		assertThat(symbols(context).subList(0, 5)).containsExactly("Order", "Order#small-methods",
				"Order#Order(String,BigDecimal)", "Order#markRefunded", "Order#small-methods-2");
	}

	@Test
	void aMethodNameAlsoFindsItsOverloads() {
		RetrievedContext context = retriever.forTopic(project, query("Constructor", List.of(), List.of("Order#Order")),
				6000);

		assertThat(symbols(context).getFirst()).isEqualTo("Order#Order(String,BigDecimal)");
	}

	@Test
	void theTokenBudgetHoldsAndSmallerChunksStillFitAfterABigOne() {
		RetrievedContext context = retriever.forTopic(project,
				query("Refund rules", List.of(ORDER + "OrderService.java"), List.of("OrderService#refund")), 300);

		assertThat(context.totalTokens()).isLessThanOrEqualTo(300)
				.isEqualTo(context.chunks().stream().mapToInt(RetrievedChunk::tokenEstimate).sum());
		assertThat(symbols(context).getFirst()).isEqualTo("OrderService#refund");   // 192 tokens
		assertThat(symbols(context)).doesNotContain("OrderService")                 // 145 more would not fit
				.contains("OrderService#find");                                     // 34 still does
	}

	@Test
	void atLeastOneChunkEvenWhenItAloneIsOverBudget() {
		RetrievedContext context = retriever.forTopic(project, query("Refund", List.of(), List.of("OrderService#refund")),
				1);

		assertThat(symbols(context)).containsExactly("OrderService#refund");
	}

	@Test
	void testFilesComeFromSearchOnlyForTestingTopics() {
		String text = "refund cannot exceed what was paid";

		RetrievedContext production = retriever.forTopic(project, new TopicQuery(text, List.of(), List.of(), false), 6000);
		RetrievedContext testing = retriever.forTopic(project, new TopicQuery(text, List.of(), List.of(), true), 6000);

		assertThat(production.chunks()).extracting(RetrievedChunk::path).noneMatch(path -> path.startsWith("src/test/"));
		assertThat(testing.chunks()).extracting(RetrievedChunk::symbol)
				.contains("OrderServiceTest#refundCannotExceedWhatWasPaid");
	}

	@Test
	void aTestFileThePlanNamesIsAlwaysIncluded() {
		RetrievedContext context = retriever.forTopic(project, query("Tests",
				List.of("src/test/java/com/example/shop/order/OrderServiceTest.java"), List.of()), 6000);

		assertThat(symbols(context)).startsWith("OrderServiceTest");
	}

	@Test
	void aLineRangeGetsTheChunksItFallsInNotTheTypeHeader() {
		RetrievedContext context = retriever.forRanges(project, List.of(new LineRange(ORDER + "OrderService.java", 30, 35)),
				6000);

		assertThat(symbols(context)).containsExactly("OrderService#refund");
	}

	@Test
	void aLineRangeOnFieldsFallsBackToTheTypeHeader() {
		RetrievedContext context = retriever.forRanges(project, List.of(new LineRange(ORDER + "Order.java", 20, 25)), 6000);

		assertThat(symbols(context)).containsExactly("Order");   // no method chunk covers the fields
	}

	@Test
	void severalRangesKeepTheirOrderAndEachChunkOnce() {
		RetrievedContext context = retriever.forRanges(project, List.of(
				new LineRange(ORDER + "OrderController.java", 27, 30),
				new LineRange(ORDER + "OrderService.java", 17, 42),
				new LineRange(ORDER + "OrderController.java", 28, 29)), 6000);

		assertThat(symbols(context)).containsExactly("OrderController#refund", "OrderService#find",
				"OrderService#place", "OrderService#refund");
	}

	private static TopicQuery query(String text, List<String> paths, List<String> symbols) {
		return new TopicQuery(text, paths, symbols, false);
	}

	private static List<String> symbols(RetrievedContext context) {
		return context.chunks().stream().map(RetrievedChunk::symbol).toList();
	}

}
