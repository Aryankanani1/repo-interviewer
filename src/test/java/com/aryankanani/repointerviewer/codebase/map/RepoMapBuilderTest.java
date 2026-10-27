package com.aryankanani.repointerviewer.codebase.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.chunk.TestFiles;
import com.aryankanani.repointerviewer.codebase.scan.FileClassifier;
import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import com.aryankanani.repointerviewer.common.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Task 1.7: the repo map format, the token budget and the order in which roles shrink (LLD §6.9). */
class RepoMapBuilderTest {

	/** The whole fixture repository fits in the default 12K budget, so nothing is shrunk. */
	private static final String FIXTURE_MAP = """
			./
			  README.md [DOC]
			  pom.xml [BUILD]
			src/main/java/com/example/shop/
			  ShopApplication [OTHER] @SpringBootApplication
			    + main(String[] args): void
			src/main/java/com/example/shop/order/
			  Order [ENTITY] @Entity @Table(name = "orders")
			    enum Status { PAID, REFUNDED }
			    - id: UUID @Id @GeneratedValue
			    - customerEmail: String @Column(nullable = false)
			    - total: BigDecimal @Column(nullable = false)
			    - refundedAmount: BigDecimal @Column(nullable = false)
			    - status: Status @Enumerated(EnumType.STRING) @Column(nullable = false)
			    - createdAt: Instant @Column(nullable = false, updatable = false)
			  OrderController [CONTROLLER] @RestController @RequestMapping("/orders")
			    + get(UUID id): Order @GetMapping("/{id}")
			    + refund(UUID id, RefundRequest request): Order @PostMapping("/{id}/refund")
			  OrderRepository [REPOSITORY] extends JpaRepository<Order,UUID>
			    + findByCustomerEmailOrderByCreatedAtDesc(String customerEmail): List<Order>
			  OrderService [SERVICE] @Service
			    + find(UUID id): Order @Transactional(readOnly = true)
			    + place(String customerEmail, BigDecimal total): Order @Transactional
			    + refund(UUID orderId, RefundRequest request): Order @Transactional
			  RefundRequest [DTO] record(BigDecimal amount)
			src/main/resources/
			  application.yml [CONFIG]
			src/main/resources/db/migration/
			  V1__orders.sql [MIGRATION]
			src/test/java/com/example/shop/order/
			  OrderServiceTest [TEST] @ExtendWith(MockitoExtension.class)
			    refundCannotExceedWhatWasPaid(): void @Test""";

	@Test
	void fixtureMapWithTheDefaultBudget() {
		assertThat(new RepoMapBuilder(12_000).build(fixture())).isEqualTo(FIXTURE_MAP);
	}

	@ParameterizedTest
	@ValueSource(ints = { 12_000, 500, 200, 100, 50, 10 })
	void fixtureMapStaysWithinBudget(int budget) {
		String map = new RepoMapBuilder(budget).build(fixture());

		assertThat(TokenEstimator.estimate(map)).isLessThanOrEqualTo(budget);
	}

	/** Acceptance criterion: with a small budget, tests and DTOs become counts while the endpoints stay. */
	@Test
	void smallBudgetCollapsesTestsAndDtosBeforeControllersAndServices() {
		String expected = """
				src/main/java/com/acme/
				  OrderController [CONTROLLER] @RestController
				    + get(UUID id): Order @GetMapping("/orders/{id}")
				    + create(OrderRequest request): Order @PostMapping("/orders")
				  OrderService [SERVICE] @Service
				    + place(OrderRequest request): Order @Transactional
				    + cancel(UUID id): Order @Transactional
				src/main/java/com/acme/dto/
				  30 more DTO files
				src/test/java/com/acme/
				  30 more TEST files""";

		String map = new RepoMapBuilder(TokenEstimator.estimate(expected)).build(syntheticRepo());

		assertThat(map).isEqualTo(expected);
	}

	/** Every budget, not just one: the shrink order holds all the way down. */
	@Test
	void everyBudgetKeepsThePriorityOrder() {
		List<RepoFile> repo = syntheticRepo();
		int fullSize = TokenEstimator.estimate(new RepoMapBuilder(100_000).build(repo));

		for (int budget = fullSize; budget >= 1; budget -= 3) {
			String map = new RepoMapBuilder(budget).build(repo);

			assertThat(TokenEstimator.estimate(map)).as("budget %d", budget).isLessThanOrEqualTo(budget);
			if (!map.contains("+ get(UUID id)")) {
				assertThat(map).as("budget %d: SERVICE shrinks before CONTROLLER", budget)
						.doesNotContain("+ place(OrderRequest request)");
			}
			if (!map.contains("+ place(OrderRequest request)")) {
				assertThat(map).as("budget %d: TEST and DTO shrink before SERVICE", budget)
						.doesNotContain("Item0Request [DTO]", "Check0Test [TEST]");
			}
		}
	}

	@Test
	void aRoleLosesItsMembersBeforeItsNames() {
		List<RepoFile> repo = syntheticRepo();
		int fullSize = TokenEstimator.estimate(new RepoMapBuilder(100_000).build(repo));

		// One token too many: TEST, the first role present in the shrink order, drops only its methods.
		String map = new RepoMapBuilder(fullSize - 1).build(repo);

		assertThat(map).contains("  Check0Test [TEST]\n", "  Item0Request [DTO]").doesNotContain("@Test");
	}

	@Test
	void whenEvenCountsDoNotFitTheMapIsCutAndMarked() {
		assertThat(new RepoMapBuilder(10).build(fixture())).isEqualTo("./\n" + RepoMapBuilder.TRUNCATED);
		assertThat(new RepoMapBuilder(1).build(fixture())).isEmpty();
	}

	@Test
	void emptyRepositoryGivesAnEmptyMap() {
		assertThat(new RepoMapBuilder(100).build(List.of())).isEmpty();
	}

	private static List<RepoFile> fixture() {
		FileClassifier classifier = new FileClassifier();
		return LocalDirectoryRepoFetcher.FIXTURE_FILES.stream()
				.map(path -> new RepoFile(TestFiles.fixture(path), classifier.classify(TestFiles.scanned(path))))
				.toList();
	}

	/** One controller and one service, buried under 30 DTOs and 30 test classes. */
	private static List<RepoFile> syntheticRepo() {
		List<RepoFile> files = new ArrayList<>();
		files.add(file("src/main/java/com/acme/OrderController.java", FileRole.CONTROLLER, """
				package com.acme;
				@RestController
				public class OrderController {
				    @GetMapping("/orders/{id}") public Order get(UUID id) { return null; }
				    @PostMapping("/orders") public Order create(OrderRequest request) { return null; }
				}
				"""));
		files.add(file("src/main/java/com/acme/OrderService.java", FileRole.SERVICE, """
				package com.acme;
				@Service
				public class OrderService {
				    @Transactional public Order place(OrderRequest request) { return null; }
				    @Transactional public Order cancel(UUID id) { return null; }
				}
				"""));
		for (int i = 0; i < 30; i++) {
			files.add(file("src/main/java/com/acme/dto/Item" + i + "Request.java", FileRole.DTO,
					"package com.acme.dto;\npublic record Item" + i + "Request(String name, int quantity) {}\n"));
			files.add(file("src/test/java/com/acme/Check" + i + "Test.java", FileRole.TEST,
					"package com.acme;\nclass Check" + i + "Test {\n    @Test void works() {}\n"
							+ "    @Test void failsOnBadInput() {}\n}\n"));
		}
		return files;
	}

	private static RepoFile file(String path, FileRole role, String source) {
		return new RepoFile(TestFiles.text(path, source), role);
	}

}
