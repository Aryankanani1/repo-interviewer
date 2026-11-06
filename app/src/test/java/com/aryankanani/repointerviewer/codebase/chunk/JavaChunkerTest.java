package com.aryankanani.repointerviewer.codebase.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.common.TokenEstimator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Line numbers refer to the fixture files under src/test/resources/fixtures/sample-repo. */
class JavaChunkerTest {

	private static final String ORDER = "src/main/java/com/example/shop/order/";

	private final JavaChunker chunker = new JavaChunker(120, 4, new TextChunker(120));

	@Test
	void refundMethodChunkIsExactlyItsJavadocThroughItsClosingBrace() {
		RedactedFile file = TestFiles.fixture(ORDER + "OrderService.java");

		Chunk refund = bySymbol(chunker.chunk(file), "OrderService#refund");

		assertThat(refund.chunkType()).isEqualTo(ChunkType.METHOD);
		assertThat(refund.startLine()).isEqualTo(27);   // the first line of the Javadoc
		assertThat(refund.endLine()).isEqualTo(42);     // the closing brace
		assertThat(refund.content()).isEqualTo(lines(file, 27, 42)).startsWith("    /**").endsWith("    }");
		assertThat(refund.tokenEstimate()).isEqualTo(TokenEstimator.estimate(refund.content()));
	}

	@Test
	void serviceGetsAHeaderAConstructorRunAndOneChunkPerMethod() {
		List<Chunk> chunks = chunker.chunk(TestFiles.fixture(ORDER + "OrderService.java"));

		assertThat(chunks).extracting(Chunk::symbol, Chunk::chunkType, Chunk::startLine, Chunk::endLine)
				.containsExactly(
						tuple("OrderService", ChunkType.TYPE_HEADER, 8, 43),
						tuple("OrderService#small-methods", ChunkType.METHOD, 13, 15),   // the 3-line constructor
						tuple("OrderService#find", ChunkType.METHOD, 17, 20),
						tuple("OrderService#place", ChunkType.METHOD, 22, 25),
						tuple("OrderService#refund", ChunkType.METHOD, 27, 42));
	}

	@Test
	void typeHeaderListsFieldsAndSignaturesButNoBodiesOrJavadoc() {
		Chunk header = bySymbol(chunker.chunk(TestFiles.fixture(ORDER + "OrderService.java")), "OrderService");

		assertThat(header.content())
				.startsWith("package com.example.shop.order;\n\nimport java.math.BigDecimal;\n")
				.contains("@Service\npublic class OrderService {\n")
				.contains("    private final OrderRepository orderRepository;\n")
				.contains("    public OrderService(OrderRepository orderRepository);\n")
				.contains("    @Transactional(readOnly = true)\n    public Order find(UUID id);\n")
				.contains("    @Transactional\n    public Order refund(UUID orderId, RefundRequest request);\n")
				.endsWith("\n}")
				.doesNotContain("orderRepository.save")
				.doesNotContain("Refunds part or all");
	}

	@Test
	void signaturesKeepParameterAnnotations() {
		Chunk header = bySymbol(chunker.chunk(TestFiles.fixture(ORDER + "OrderController.java")), "OrderController");

		assertThat(header.content())
				.contains("@RestController\n@RequestMapping(\"/orders\")\npublic class OrderController {\n")
				.contains("    @PostMapping(\"/{id}/refund\")\n"
						+ "    public Order refund(@PathVariable UUID id, @Valid @RequestBody RefundRequest request);\n");
	}

	@Test
	void smallMethodsMergePerRunAndOverloadedConstructorsGetParameterTypes() {
		List<Chunk> chunks = chunker.chunk(TestFiles.fixture(ORDER + "Order.java"));

		assertThat(chunks).extracting(Chunk::symbol, Chunk::startLine, Chunk::endLine)
				.containsExactly(
						tuple("Order", 14, 78),
						tuple("Order.Status", 18, 18),
						tuple("Order#small-methods", 40, 41),            // protected Order() { }
						tuple("Order#Order(String,BigDecimal)", 43, 46),
						tuple("Order#markRefunded", 48, 53),
						tuple("Order#small-methods-2", 55, 77));         // the six getters
		assertThat(bySymbol(chunks, "Order").content())
				.contains("@Entity\n@Table(name = \"orders\")\npublic class Order {\n")
				.contains("    public enum Status { ... }\n")
				.contains("    @Id\n    @GeneratedValue\n    private UUID id;\n");
		assertThat(bySymbol(chunks, "Order.Status").content()).contains("public enum Status {").contains("PAID, REFUNDED");
	}

	@Test
	void longMethodIsCutIntoWindowsThatOverlapByTenLines() {
		StringBuilder source = new StringBuilder("class Big {\n    void run() {\n");
		for (int i = 0; i < 48; i++) {
			source.append("        step(").append(i).append(");\n");
		}
		source.append("    }\n}\n");   // run() is lines 2 to 51: 50 lines

		List<Chunk> chunks = new JavaChunker(20, 4, new TextChunker(20))
				.chunk(TestFiles.text("src/main/java/Big.java", source.toString()));

		assertThat(chunks).filteredOn(chunk -> chunk.chunkType() == ChunkType.METHOD)
				.extracting(Chunk::symbol, Chunk::startLine, Chunk::endLine)
				.containsExactly(
						tuple("Big#run", 2, 21),
						tuple("Big#run#part2", 12, 31),
						tuple("Big#run#part3", 22, 41),
						tuple("Big#run#part4", 32, 51));
	}

	@Test
	void javaThatDoesNotParseFallsBackToTextChunks() {
		List<Chunk> chunks = chunker.chunk(TestFiles.text("src/main/java/Broken.java", "class Broken {\n    void a( {\n}\n"));

		assertThat(chunks).extracting(Chunk::chunkType, Chunk::symbol, Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(ChunkType.TEXT, null, 1, 3));
	}

	@Test
	void fileWithoutATypeBecomesText() {
		List<Chunk> chunks = chunker.chunk(TestFiles.text("src/main/java/com/acme/package-info.java",
				"/** Orders: placing and refunding. */\npackage com.acme;\n"));

		assertThat(chunks).extracting(Chunk::chunkType, Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(ChunkType.TEXT, 1, 2));
	}

	@Test
	void secretInAFieldNeverReachesTheHeader() {
		String key = "sk-" + "ant-" + "x".repeat(30);   // glued at runtime, see SecretRedactorTest

		List<Chunk> chunks = chunker.chunk(TestFiles.text("src/main/java/Keys.java",
				"class Keys {\n    static final String KEY = \"" + key + "\";\n}\n"));

		assertThat(bySymbol(chunks, "Keys").content()).contains("<REDACTED:anthropic_key>").doesNotContain("sk-ant");
	}

	private static Chunk bySymbol(List<Chunk> chunks, String symbol) {
		return chunks.stream().filter(chunk -> symbol.equals(chunk.symbol())).findFirst().orElseThrow();
	}

	private static String lines(RedactedFile file, int start, int end) {
		return String.join("\n", file.content().lines().toList().subList(start - 1, end));
	}

}
