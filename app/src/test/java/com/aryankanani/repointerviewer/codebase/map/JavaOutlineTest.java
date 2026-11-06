package com.aryankanani.repointerviewer.codebase.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.codebase.chunk.TestFiles;
import com.aryankanani.repointerviewer.codebase.map.JavaOutline.TypeOutline;
import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JavaOutlineTest {

	@Test
	void controllerShowsEndpointsWithoutConstructorsOrParameterAnnotations() {
		TypeOutline outline = single(FileRole.CONTROLLER, """
				@RestController
				@RequestMapping("/orders")
				public class OrderController {
				    private final OrderService orderService;
				    public OrderController(OrderService orderService) { this.orderService = orderService; }
				    @PostMapping("/{id}/refund")
				    public Order refund(@PathVariable UUID id, @Valid @RequestBody final RefundRequest request) {
				        return orderService.refund(id, request);
				    }
				    private void audit() {}
				}
				""");

		assertThat(outline.line()).isEqualTo("OrderController [CONTROLLER] @RestController @RequestMapping(\"/orders\")");
		assertThat(outline.members())
				.containsExactly("+ refund(UUID id, RefundRequest request): Order @PostMapping(\"/{id}/refund\")");
	}

	@Test
	void entityShowsFieldsButNotAccessorsOrObjectMethods() {
		TypeOutline outline = single(FileRole.ENTITY, """
				@Entity
				public class Order {
				    private static final long serialVersionUID = 1L;
				    @Id @GeneratedValue private UUID id;
				    @Version private long version;
				    private String note, tag;
				    public UUID getId() { return id; }
				    public boolean isPaid() { return true; }
				    public void setNote(String note) { this.note = note; }
				    public void markPaid() {}
				    void markRefunded() {}
				    @Override public boolean equals(Object other) { return false; }
				    @Override public int hashCode() { return 0; }
				    @Override public String toString() { return ""; }
				}
				""");

		assertThat(outline.members()).containsExactly(
				"- id: UUID @Id @GeneratedValue",
				"- version: long @Version",
				"- note: String",
				"- tag: String",
				"+ markPaid(): void");
	}

	@Test
	void fieldsAreShownOnlyForEntities() {
		TypeOutline outline = single(FileRole.SERVICE, """
				@Service
				public class OrderService {
				    private final OrderRepository repository;
				    public OrderService(OrderRepository repository) { this.repository = repository; }
				}
				""");

		assertThat(outline.members()).isEmpty();
	}

	@Test
	void recordShowsItsComponentsAndInterfaces() {
		TypeOutline outline = single(FileRole.DTO, """
				public record RefundRequest(@NotNull @Positive BigDecimal amount, String reason) implements Serializable {
				    public static RefundRequest full(BigDecimal amount) { return new RefundRequest(amount, null); }
				}
				""");

		assertThat(outline.line())
				.isEqualTo("RefundRequest [DTO] record(BigDecimal amount, String reason) implements Serializable");
		assertThat(outline.members()).containsExactly("+ full(BigDecimal amount): RefundRequest");
	}

	@Test
	void interfaceMethodsArePublicUnlessPrivate() {
		TypeOutline outline = single(FileRole.REPOSITORY, """
				public interface OrderRepository extends JpaRepository<Order, UUID>, CustomOrders {
				    @Query("select o from Order o where o.status = :status")
				    List<Order> findByStatus(Status status);
				    default List<Order> paid() { return findByStatus(Status.PAID); }
				    private void helper() {}
				}
				""");

		assertThat(outline.line())
				.isEqualTo("OrderRepository [REPOSITORY] extends JpaRepository<Order,UUID>, CustomOrders");
		assertThat(outline.members()).containsExactly(
				"+ findByStatus(Status status): List<Order> @Query(\"select o from Order o where o.status = :status\")",
				"+ paid(): List<Order>");
	}

	@Test
	void testClassShowsItsTestMethodsEvenWhenNotPublic() {
		TypeOutline outline = single(FileRole.TEST, """
				@ExtendWith(MockitoExtension.class)
				class OrderServiceTest {
				    @BeforeEach void setUp() {}
				    @Test void refundCannotExceedWhatWasPaid() {}
				    @ParameterizedTest @ValueSource(ints = { 1, 2 }) void acceptsSmallAmounts(int amount) {}
				    private Order paidOrder() { return null; }
				}
				""");

		assertThat(outline.members()).containsExactly(
				"refundCannotExceedWhatWasPaid(): void @Test",
				"acceptsSmallAmounts(int amount): void @ParameterizedTest @ValueSource(ints = { 1, 2 })");
	}

	@Test
	void testMethodsOutsideTestFilesFollowTheNormalRule() {
		TypeOutline outline = single(FileRole.OTHER, """
				class Helper {
				    @Test void looksLikeATest() {}
				}
				""");

		assertThat(outline.members()).isEmpty();
	}

	@Test
	void nestedTypesGetOneLineEach() {
		TypeOutline outline = single(FileRole.ENTITY, """
				@Entity
				public class Order {
				    public enum Status { PAID, REFUNDED }
				    public record Line(String sku, int quantity) {}
				    static class Builder {}
				    interface Listener {}
				}
				""");

		assertThat(outline.members()).containsExactly(
				"enum Status { PAID, REFUNDED }",
				"record Line(String sku, int quantity)",
				"class Builder",
				"interface Listener");
	}

	@Test
	void longEnumShowsTheFirstTwentyConstants() {
		String constants = IntStream.range(0, 25).mapToObj(i -> "C" + i).collect(Collectors.joining(", "));
		TypeOutline outline = single(FileRole.OTHER, "public enum Code { " + constants + " }");

		String shown = IntStream.range(0, 20).mapToObj(i -> "C" + i).collect(Collectors.joining(", "));
		assertThat(outline.line()).isEqualTo("Code [OTHER] enum { " + shown + ", +5 more }");
	}

	@Test
	void longAnnotationIsPutOnOneLineAndCut() {
		TypeOutline outline = single(FileRole.REPOSITORY, """
				public interface OrderRepository {
				    @Query(\"""
				            select o from Order o
				            where o.status = 'PAID' and o.total > 100 and o.createdAt > :since and o.customerEmail like :pattern
				            \""")
				    List<Order> bigPaidOrders(Instant since, String pattern);
				}
				""");

		String member = outline.members().getFirst();
		String annotation = member.substring(member.indexOf(" @Query"));
		assertThat(annotation).doesNotContain("\n").endsWith("…");
		assertThat(annotation.strip()).hasSize(JavaOutline.MAX_ANNOTATION_CHARS);
	}

	@Test
	void overrideAndVarargsAndTypeParameters() {
		TypeOutline outline = single(FileRole.OTHER, """
				public class Box<T extends Comparable<T>> implements Supplier<T> {
				    @Override public T get() { return null; }
				    public void tag(String... tags) {}
				}
				""");

		assertThat(outline.line()).isEqualTo("Box<T extends Comparable<T>> [OTHER] implements Supplier<T>");
		assertThat(outline.members()).containsExactly("+ get(): T", "+ tag(String... tags): void");
	}

	@Test
	void everyTopLevelTypeGetsAnOutline() {
		List<TypeOutline> outlines = outline(FileRole.OTHER, "class A {}\nclass B {}");

		assertThat(outlines).extracting(TypeOutline::line).containsExactly("A [OTHER]", "B [OTHER]");
	}

	@Test
	void fileThatDoesNotParseOrHasNoTypeGivesNoOutline() {
		assertThat(outline(FileRole.OTHER, "public class Broken {")).isEmpty();
		assertThat(JavaOutline.of(TestFiles.text("src/main/java/com/acme/package-info.java", "package com.acme;"),
				FileRole.OTHER)).isEmpty();
	}

	private static TypeOutline single(FileRole role, String source) {
		List<TypeOutline> outlines = outline(role, source);
		assertThat(outlines).hasSize(1);
		return outlines.getFirst();
	}

	private static List<TypeOutline> outline(FileRole role, String source) {
		return JavaOutline.of(TestFiles.text("src/main/java/com/acme/Example.java", source), role);
	}

}
