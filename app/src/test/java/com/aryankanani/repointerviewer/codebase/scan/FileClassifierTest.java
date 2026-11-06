package com.aryankanani.repointerviewer.codebase.scan;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class FileClassifierTest {

	private static final String SHOP = "src/main/java/com/example/shop/";

	private final FileClassifier classifier = new FileClassifier();

	@TempDir
	Path tempDir;

	@Test
	void fixtureFilesGetTheirRoles() {
		GithubUrl url = GithubUrl.parse("https://github.com/acme/shop");
		RemoteHead head = new RemoteHead(LocalDirectoryRepoFetcher.DEFAULT_SHA, "main");
		Path repo = new LocalDirectoryRepoFetcher().cloneShallow(url, head, tempDir).directory();

		Map<String, FileRole> roles = new FileScanner(1500, 300_000).scan(repo).files().stream()
				.collect(Collectors.toMap(ScannedFile::path, classifier::classify));

		assertThat(roles).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
				entry("README.md", FileRole.DOC),
				entry("pom.xml", FileRole.BUILD),
				entry(SHOP + "ShopApplication.java", FileRole.OTHER),
				entry(SHOP + "order/Order.java", FileRole.ENTITY),
				entry(SHOP + "order/OrderController.java", FileRole.CONTROLLER),
				entry(SHOP + "order/OrderRepository.java", FileRole.REPOSITORY),
				entry(SHOP + "order/OrderService.java", FileRole.SERVICE),
				entry(SHOP + "order/RefundRequest.java", FileRole.DTO),
				entry("src/main/resources/application.yml", FileRole.CONFIG),
				entry("src/main/resources/db/migration/V1__orders.sql", FileRole.MIGRATION),
				entry("src/test/java/com/example/shop/order/OrderServiceTest.java", FileRole.TEST)));
	}

	@ParameterizedTest(name = "{0} is {2}")
	@MethodSource("javaFiles")
	void classifiesJavaByItsMainType(String fileName, String source, FileRole expected) {
		assertThat(classifier.classify(file("src/main/java/com/acme/" + fileName, source))).isEqualTo(expected);
	}

	static Stream<Arguments> javaFiles() {
		return Stream.of(
				// SECURITY wins over CONFIG: this is a @Configuration class too.
				arguments("SecurityConfig.java", """
						@Configuration
						@EnableWebSecurity
						class SecurityConfig {
						    @Bean
						    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
						        return http.build();
						    }
						}
						""", FileRole.SECURITY),
				arguments("JwtAuthFilter.java", "class JwtAuthFilter extends OncePerRequestFilter {}",
						FileRole.SECURITY),
				arguments("RequestLoggingFilter.java", "class RequestLoggingFilter extends OncePerRequestFilter {}",
						FileRole.OTHER),
				arguments("WebConfig.java", "@Configuration class WebConfig {}", FileRole.CONFIG),
				arguments("ShopProperties.java",
						"@ConfigurationProperties(prefix = \"shop\") record ShopProperties(int maxItems) {}",
						FileRole.CONFIG),
				arguments("ProductController.java",
						"@Controller class ProductController { @QueryMapping List<Product> products() { return null; } }",
						FileRole.CONTROLLER),
				arguments("ProductMutations.java",
						"class ProductMutations { @MutationMapping Product addProduct() { return null; } }",
						FileRole.CONTROLLER),
				arguments("AdminApi.java", "@RequestMapping(\"/admin\") class AdminApi {}", FileRole.CONTROLLER),
				arguments("OrderListener.java",
						"@Component class OrderListener { @KafkaListener(topics = \"orders\") void on(String m) {} }",
						FileRole.MESSAGING),
				// MESSAGING wins over SERVICE.
				arguments("ReceiptMailer.java",
						"@Service class ReceiptMailer { @TransactionalEventListener void on(OrderPlaced e) {} }",
						FileRole.MESSAGING),
				arguments("RefundHandler.java", "@Component class RefundHandler {}", FileRole.SERVICE),
				arguments("SystemClock.java", "@Component class SystemClock {}", FileRole.OTHER),
				arguments("PaymentService.java", "@org.springframework.stereotype.Service class PaymentService {}",
						FileRole.SERVICE),
				arguments("ProductRepository.java",
						"interface ProductRepository extends CrudRepository<Product, Long> {}", FileRole.REPOSITORY),
				arguments("JdbcOrderDao.java", "@Repository class JdbcOrderDao {}", FileRole.REPOSITORY),
				arguments("Address.java", "@Embeddable class Address {}", FileRole.ENTITY),
				arguments("OrderResponse.java", "record OrderResponse(UUID id) {}", FileRole.DTO),
				arguments("CreateOrderCommand.java", "class CreateOrderCommand {}", FileRole.DTO),
				arguments("ProductDTO.java", "class ProductDTO {}", FileRole.DTO),
				// Not every record is a DTO: only the names in the DTO rule.
				arguments("Money.java", "record Money(BigDecimal amount) {}", FileRole.OTHER),
				// The type named like the file decides, not the first one in it.
				arguments("Outer.java", "class Helper {}\n@Service class Outer {}", FileRole.SERVICE),
				// Does not parse: only the file name is left.
				arguments("RefundRequest.java", "public record RefundRequest(", FileRole.DTO),
				arguments("Broken.java", "class Broken {", FileRole.OTHER),
				arguments("package-info.java", "package com.acme;", FileRole.OTHER));
	}

	@ParameterizedTest(name = "{0} is {1}")
	@CsvSource({
			// A path rule beats annotations: the content of every file here is a @Service class.
			"src/test/java/com/acme/PaymentServiceTest.java,     TEST",
			"src/test/resources/application-test.yml,            TEST",
			"src/main/resources/db/migration/V2__add_index.sql,  MIGRATION",
			"src/main/resources/schema.sql,                      OTHER",
			"module-a/pom.xml,                                   BUILD",
			"compose.yaml,                                       BUILD",
			".github/workflows/ci.yml,                           BUILD",
			"src/main/resources/application-prod.properties,     CONFIG",
			"docs/README.md,                                     DOC" })
	void pathRulesComeFirst(String path, FileRole expected) {
		assertThat(classifier.classify(file(path, "@Service public class PaymentService {}"))).isEqualTo(expected);
	}

	private static ScannedFile file(String path, String content) {
		return new ScannedFile(path, FilePatterns.language(path), content, (int) content.lines().count(),
				content.length(), "unused");
	}

}
