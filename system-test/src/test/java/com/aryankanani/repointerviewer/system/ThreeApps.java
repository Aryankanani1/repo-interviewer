package com.aryankanani.repointerviewer.system;

import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeModelServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.GroupListing;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The whole system for the system test (task 4.1 step 4b): one Postgres with both services' {@code bootstrap.sql}
 * (each service logs in as its own user, as deployed), one Kafka broker, {@link FakeModelServer} for Claude and
 * Ollama, Jaeger for their traces (task 4.3), and the three apps' jars as processes, with the {@code stage} and
 * {@code kafka} profiles and their settings as command-line arguments, as a deployment would give them.
 */
final class ThreeApps implements AutoCloseable {

	private static final Duration START_TIMEOUT = Duration.ofMinutes(3);

	private static final int OTLP_HTTP = 4318;

	private static final int JAEGER_API = 16686;

	private final PostgreSQLContainer postgres;

	private final KafkaContainer kafka;

	private final FakeModelServer models;

	private final GenericContainer<?> jaeger;

	private final List<AppProcess> apps = new ArrayList<>();

	private AppProcess analysis;

	private AppProcess interview;

	private AppProcess gateway;

	private ThreeApps() throws Exception {
		Path root = Path.of(System.getProperty("system.root", ".."));
		postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17")
				.asCompatibleSubstituteFor("postgres"))
				.withCopyFileToContainer(MountableFile.forHostPath(
						root.resolve("analysis-service/src/main/resources/db/bootstrap.sql")),
						"/docker-entrypoint-initdb.d/10-analysis.sql")
				.withCopyFileToContainer(MountableFile.forHostPath(
						root.resolve("interview-service/src/main/resources/db/bootstrap.sql")),
						"/docker-entrypoint-initdb.d/20-interview.sql");
		kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2")).withStartupAttempts(3);
		models = new FakeModelServer();
		jaeger = new GenericContainer<>(DockerImageName.parse("jaegertracing/jaeger:2.23.0"))   // as in Compose
				.withExposedPorts(OTLP_HTTP, JAEGER_API).waitingFor(Wait.forHttp("/").forPort(JAEGER_API));
	}

	/** Starts everything; the three apps start side by side, each with its own random port. */
	static ThreeApps start() throws Exception {
		ThreeApps system = new ThreeApps();
		try {
			system.postgres.start();
			system.kafka.start();
			system.jaeger.start();
			system.analysis = system.app("analysis-service", List.of(
					"--spring.datasource.url=" + system.postgres.getJdbcUrl(),
					"--spring.datasource.username=analysis_svc", "--spring.datasource.password=analysis_svc",
					"--spring.ai.ollama.base-url=" + system.models.baseUrl()));
			system.interview = system.app("interview-service", List.of(
					"--spring.datasource.url=" + system.postgres.getJdbcUrl(),
					"--spring.datasource.username=interview_svc", "--spring.datasource.password=interview_svc",
					"--spring.http.serviceclient.analysis.base-url=" + system.analysis.url(),
					// Off so one interview's model calls are exactly the ones it needs (no question written ahead).
					"--app.interview.prefetch-enabled=false"));
			system.gateway = system.app("graphql-gateway", List.of(
					"--spring.http.serviceclient.analysis.base-url=" + system.analysis.url(),
					"--spring.http.serviceclient.interview.base-url=" + system.interview.url()));
			for (AppProcess app : system.apps) {
				app.awaitReady(START_TIMEOUT);
			}
			return system;
		}
		catch (Exception | Error ex) {
			system.close();
			throw ex;
		}
	}

	String gatewayUrl() {
		return gateway.url();
	}

	String adminJdbcUrl() {
		return postgres.getJdbcUrl();
	}

	String adminUser() {
		return postgres.getUsername();
	}

	String adminPassword() {
		return postgres.getPassword();
	}

	/** Jaeger's query API, for example {@code /api/traces/<trace id>}. */
	String jaegerUrl() {
		return "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(JAEGER_API);
	}

	AppProcess analysisService() {
		return analysis;
	}

	AppProcess interviewService() {
		return interview;
	}

	/**
	 * Until the gateway's fan-out group holds its partitions. It starts at the newest event ({@code latest}), so an
	 * event sent before that would never reach a subscriber.
	 */
	void awaitFanOutReady() {
		Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
		try (Admin admin = Admin.create(config)) {
			await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
					.until(() -> fanOutAssigned(admin));
		}
	}

	private static boolean fanOutAssigned(Admin admin) throws ExecutionException, InterruptedException {
		List<String> groups = admin.listGroups().all().get().stream().map(GroupListing::groupId)
				.filter(id -> id.startsWith("interview-events-")).toList();
		if (groups.isEmpty()) {
			return false;
		}
		ConsumerGroupDescription group = admin.describeConsumerGroups(groups).all().get().get(groups.getFirst());
		return group.members().stream().mapToLong(member -> member.assignment().topicPartitions().size()).sum() == 6;
	}

	private AppProcess app(String module, List<String> own) throws Exception {
		List<String> arguments = new ArrayList<>(List.of("--spring.profiles.active=stage,kafka",
				"--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
				"--spring.ai.anthropic.base-url=" + models.baseUrl(),
				"--spring.ai.anthropic.api-key=test-key-not-used", "--spring.ai.anthropic.max-retries=0",
				"--management.opentelemetry.tracing.export.otlp.endpoint=http://" + jaeger.getHost() + ":"
						+ jaeger.getMappedPort(OTLP_HTTP) + "/v1/traces",
				"--management.opentelemetry.tracing.export.schedule-delay=500ms",   // spans sent within a second
				"--logging.level.com.aryankanani.repointerviewer=INFO"));
		arguments.addAll(own);
		AppProcess app = AppProcess.start(module, arguments);
		apps.add(app);
		return app;
	}

	@Override
	public void close() throws Exception {
		for (AppProcess app : apps.reversed()) {
			app.close();
		}
		models.close();
		jaeger.stop();
		kafka.stop();
		postgres.stop();
	}

}
