package com.aryankanani.repointerviewer.codebase.embed;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.codebase.store.PendingChunk;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingTextTest {

	private static final String PATH = "src/main/java/com/example/shop/order/OrderService.java";

	@Test
	void methodChunkGetsFileClassAndMethod() {
		assertThat(EmbeddingText.document(chunk("OrderService#refund", "public Order refund() {"))).isEqualTo("""
				search_document: File: src/main/java/com/example/shop/order/OrderService.java
				Class: OrderService
				Method: refund
				public Order refund() {""");
	}

	@Test
	void typeHeaderHasNoMethodLine() {
		assertThat(EmbeddingText.document(chunk("OrderService", "public class OrderService {"))).isEqualTo("""
				search_document: File: src/main/java/com/example/shop/order/OrderService.java
				Class: OrderService
				public class OrderService {""");
	}

	@Test
	void nestedTypeKeepsItsOuterName() {
		assertThat(EmbeddingText.document(chunk("Order.Status", "enum Status"))).contains("\nClass: Order.Status\n");
	}

	@Test
	void overloadKeepsItsParameterTypes() {
		assertThat(EmbeddingText.document(chunk("OrderService#refund(UUID,RefundRequest)", "...")))
				.contains("\nMethod: refund(UUID,RefundRequest)\n");
	}

	@Test
	void windowOfALongMethodUsesThePlainMethodName() {
		assertThat(EmbeddingText.document(chunk("OrderService#refund#part2", "...")))
				.contains("\nClass: OrderService\nMethod: refund\n");
	}

	@Test
	void runOfSmallMethodsHasNoMethodLine() {
		assertThat(EmbeddingText.document(chunk("Order#small-methods-2", "int total() { return total; }")))
				.contains("\nClass: Order\n").doesNotContain("Method:");
	}

	@Test
	void textChunkWithoutSymbolHasOnlyTheFile() {
		PendingChunk config = new PendingChunk(UUID.randomUUID(), "src/main/resources/application.yml", null,
				"server:\n  port: 8080");
		assertThat(EmbeddingText.document(config)).isEqualTo("""
				search_document: File: src/main/resources/application.yml
				server:
				  port: 8080""");
	}

	@Test
	void queryGetsTheSearchQueryPrefix() {
		assertThat(EmbeddingText.query("refund a paid order")).isEqualTo("search_query: refund a paid order");
	}

	private static PendingChunk chunk(String symbol, String content) {
		return new PendingChunk(UUID.randomUUID(), PATH, symbol, content);
	}

}
