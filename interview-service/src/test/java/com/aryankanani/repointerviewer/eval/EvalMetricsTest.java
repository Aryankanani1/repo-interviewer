package com.aryankanani.repointerviewer.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.eval.EvalMetrics.AskedQuestion;
import com.aryankanani.repointerviewer.eval.EvalMetrics.Ratio;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task 1.17: the eval metrics are plain code, checked here with no model and no database. */
class EvalMetricsTest {

	@Test
	void symbolNamesAreClassAndMethodNames() {
		Set<String> names = EvalMetrics.symbolNames(List.of("Order", "Order.Status", "OrderService#refund",
				"Cart#addItem(CartItem)", "Cart#addItem(Long,int)", "Order#small-methods-2", "Order#static-initializer",
				"Order#initializer"));

		assertThat(names).containsExactlyInAnyOrder("Order", "Status", "OrderService", "refund", "Cart", "addItem");
	}

	@Test
	void aQuestionIsSpecificWhenItNamesAClassOrMethodOfTheRepo() {
		Set<String> names = Set.of("Cart", "updateTotalAmount");

		assertThat(EvalMetrics.mentionsAny("Why does Cart store its total?", names)).isTrue();
		assertThat(EvalMetrics.mentionsAny("What if a path skips updateTotalAmount()?", names)).isTrue();
		assertThat(EvalMetrics.mentionsAny("Why does the cart store its total?", names)).isFalse();   // case-sensitive
		assertThat(EvalMetrics.mentionsAny("What does CartItem do?", names)).isFalse();   // whole identifiers only
	}

	@Test
	void contentWordsDropShortWordsStopWordsAndCase() {
		assertThat(EvalMetrics.contentWords("Why does the Cart lock its row? It is a lock."))
				.containsExactlyInAnyOrder("cart", "lock", "row");
	}

	@Test
	void jaccardIsSharedWordsOverAllWords() {
		assertThat(EvalMetrics.jaccard(Set.of("cart", "lock", "row"), Set.of("cart", "lock", "version")))
				.isEqualTo(0.5);
		assertThat(EvalMetrics.jaccard(Set.of(), Set.of())).isZero();
	}

	@Test
	void duplicatePairsSkipAFollowUpAndItsOwnParent() {
		UUID main = UUID.randomUUID();
		UUID followUp = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		String text = "How does getCartForUpdate lock the cart row before updateTotalAmount runs?";

		Ratio duplicates = EvalMetrics.duplicatePairs(List.of(new AskedQuestion(main, null, text),
				new AskedQuestion(followUp, main, text), new AskedQuestion(other, null, text)));

		// Pairs: (main, other) and (followUp, other). (main, followUp) is a follow-up on its parent.
		assertThat(duplicates).isEqualTo(new Ratio(2, 2));
	}

	@Test
	void differentQuestionsAreNoDuplicates() {
		Ratio duplicates = EvalMetrics.duplicatePairs(List.of(
				new AskedQuestion(UUID.randomUUID(), null, "Why does Cart store totalAmount instead of computing it?"),
				new AskedQuestion(UUID.randomUUID(), null, "How does PaymentRecorder handle a retried Idempotency-Key?")));

		assertThat(duplicates).isEqualTo(new Ratio(0, 1));
	}

	@Test
	void gradesWithinOnePointAreConsistent() {
		assertThat(EvalMetrics.consistent(5, 6)).isTrue();
		assertThat(EvalMetrics.consistent(6, 4)).isFalse();
	}

	@Test
	void ratiosPrintAsCountAndPercentAndCompareWithTargets() {
		Ratio ratio = EvalMetrics.count(List.of(1, 2, 3, 4, 5), n -> n != 3);

		assertThat(ratio).hasToString("4/5 (80%)");
		assertThat(ratio.atLeast(0.8)).isTrue();
		assertThat(ratio.atLeast(0.95)).isFalse();
		assertThat(new Ratio(0, 0).atLeast(0)).isFalse();   // nothing measured is never a pass
	}

}
