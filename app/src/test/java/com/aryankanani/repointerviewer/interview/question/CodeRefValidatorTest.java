package com.aryankanani.repointerviewer.interview.question;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.codebase.retrieve.RetrievedContext;
import com.aryankanani.repointerviewer.codebase.store.RetrievedChunk;
import com.aryankanani.repointerviewer.interview.CodeRef;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task 1.10: a question may only cite code that was sent with the prompt (LLD §9.3). */
class CodeRefValidatorTest {

	private static final String SERVICE = "src/main/java/com/example/shop/order/OrderService.java";

	private static final String CONTROLLER = "src/main/java/com/example/shop/order/OrderController.java";

	private static final RetrievedContext CONTEXT = new RetrievedContext(List.of(
			chunk(SERVICE, 27, 42), chunk(SERVICE, 17, 20), chunk(CONTROLLER, 27, 30)), 100);

	@Test
	void aRefInsideASentChunkIsKept() {
		assertThat(validate(new CodeRefOutput(SERVICE, 31, 41))).containsExactly(new CodeRef(SERVICE, 31, 41));
	}

	@Test
	void aRefThatGoesPastTheChunkIsCutToIt() {
		assertThat(validate(new CodeRefOutput(SERVICE, 25, 60))).containsExactly(new CodeRef(SERVICE, 27, 42));
	}

	@Test
	void whenTwoChunksOverlapTheLargerOverlapWins() {
		assertThat(validate(new CodeRefOutput(SERVICE, 19, 30))).containsExactly(new CodeRef(SERVICE, 27, 30));
	}

	@Test
	void otherFilesOtherLinesAndBrokenRangesAreDropped() {
		assertThat(validate(
				new CodeRefOutput("src/main/java/com/example/shop/payment/PaymentService.java", 1, 10),
				new CodeRefOutput(SERVICE, 1, 5),          // a real file, but lines nobody sent
				new CodeRefOutput(SERVICE, 40, 30),        // end before start
				new CodeRefOutput(SERVICE, 0, 2))).isEmpty();
	}

	@Test
	void duplicatesAreKeptOnce() {
		assertThat(validate(new CodeRefOutput(CONTROLLER, 27, 30), new CodeRefOutput(CONTROLLER, 20, 35)))
				.containsExactly(new CodeRef(CONTROLLER, 27, 30));
	}

	@Test
	void retryFeedbackListsTheValidRanges() {
		assertThat(CodeRefValidator.retryFeedback(false, CONTEXT)).containsExactly(
				"No codeRef was inside the code_chunk ranges above. The only valid ranges are:",
				SERVICE + " lines 27-42", SERVICE + " lines 17-20", CONTROLLER + " lines 27-30");
		assertThat(CodeRefValidator.retryFeedback(true, CONTEXT).getFirst()).isEqualTo("The question text was empty.");
	}

	private static List<CodeRef> validate(CodeRefOutput... refs) {
		return CodeRefValidator.validate(List.of(refs), CONTEXT);
	}

	private static RetrievedChunk chunk(String path, int start, int end) {
		return new RetrievedChunk(UUID.randomUUID(), path, null, start, end, "", 1, 1.0);
	}

}
