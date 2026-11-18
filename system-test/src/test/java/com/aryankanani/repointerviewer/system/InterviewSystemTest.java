package com.aryankanani.repointerviewer.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.CannedLlmOutputs;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 4.1 step 4b: the three apps together, as deployed, driven only through the gateway's GraphQL over HTTP. Real
 * HTTP between the apps (AnalysisApi, InterviewApi), real Kafka between the interview workers and the gateway's
 * subscriptions, real Postgres with one user and schema per service. Fake only: the models ({@code FakeModelServer})
 * and the analysis of the fixture repository, which is loaded from its snapshot ({@link FixtureSeeder}). Then the
 * services are stopped one after the other, and the system must fail fast and clearly (task 4.2).
 *
 * <p>About a minute: two containers and three app starts, once for the class. App output:
 * {@code system-test/target/system-*.log}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)   // 3 and 4 read what 2 left; the last two stop the services
class InterviewSystemTest {

	private static final String ANALYSIS_UNAVAILABLE = "The analysis service is not available right now. "
			+ "Try again in a minute.";

	private static final String SUBSCRIBE = """
			subscription ($id: ID!) {
			  interviewEvents(sessionId: $id) {
			    type
			    answer { text evaluation { score verdict } }
			    nextQuestion { id order isFollowUp }
			  }
			}
			""";

	private ThreeApps apps;

	private GraphQlHttp graphQl;

	private UUID project;

	/** The session of {@link #anInterviewRunsAcrossTheThreeApps}, read again while analysis-service is down. */
	private String endedSession;

	/** The W3C trace id the answer of {@link #anInterviewRunsAcrossTheThreeApps} is sent in (task 4.3). */
	private final String answerTraceId = UUID.randomUUID().toString().replace("-", "");

	@BeforeAll
	void startTheSystem() throws Exception {
		apps = ThreeApps.start();
		project = FixtureSeeder.seed(apps.adminJdbcUrl());
		graphQl = new GraphQlHttp(apps.gatewayUrl());
	}

	@AfterAll
	void stopTheSystem() throws Exception {
		if (graphQl != null) {
			graphQl.close();
		}
		if (apps != null) {
			apps.close();
		}
	}

	@Test
	@Order(1)
	void theGatewayListsTheProjectFromAnalysisService() throws Exception {
		JsonNode response = graphQl.execute(
				"{ projects(first: 5) { edges { node { id status stats { fileCount } } } } }", Map.of());

		JsonNode node = response.at("/data/projects/edges/0/node");
		assertThat(response.has("errors")).as(response.toString()).isFalse();
		assertThat(node.get("id").asString()).isEqualTo(project.toString());
		assertThat(node.get("status").asString()).isEqualTo("READY");
		assertThat(node.at("/stats/fileCount").asInt()).isPositive();
	}

	/**
	 * Start, answer, get the grade and the next question as events, end with a report: every call of the interview
	 * crosses the gateway, interview-service and analysis-service, and the answer goes through Kafka.
	 */
	@Test
	@Order(2)
	void anInterviewRunsAcrossTheThreeApps() throws Exception {
		JsonNode started = graphQl.execute("""
				mutation ($input: StartInterviewInput!) {
				  startInterview(input: $input) {
				    session { id status }
				    firstQuestion { id order codeRefs { path startLine endLine snippet } }
				    errors { code message }
				  }
				}
				""", Map.of("input", Map.of("projectId", project.toString(), "questionCount", 3)));
		assertThat(started.at("/data/startInterview/errors").size()).as(started.toString()).isZero();
		String session = started.at("/data/startInterview/session/id").asString();
		JsonNode first = started.at("/data/startInterview/firstQuestion");
		assertThat(first.get("order").asInt()).isEqualTo(1);
		assertThat(first.at("/codeRefs/0/snippet").asString()).as("the cited code, from analysis-service").isNotBlank();

		apps.awaitFanOutReady();
		GraphQlHttp.Subscription events = graphQl.subscribe(SUBSCRIBE, Map.of("id", session));
		events.awaitOpen();
		JsonNode answered = graphQl.execute("""
				mutation ($input: SubmitAnswerInput!) {
				  submitAnswer(input: $input) {
				    answer { text evaluation { score } } nextQuestion { id } errors { code }
				  }
				}
				""", Map.of("input", Map.of("questionId", first.get("id").asString(),
				"text", "Both read the same refundedAmount, so both checks pass.")),
				Map.of("traceparent", "00-" + answerTraceId + "-00f067aa0ba902b7-01"));   // as a browser's tracing would

		// With Kafka the request only saves the answer: the grade and the next question come as events.
		assertThat(answered.at("/data/submitAnswer/errors").size()).as(answered.toString()).isZero();
		assertThat(answered.at("/data/submitAnswer/answer/evaluation").isNull()).isTrue();
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
				.until(() -> events.events().size() >= 2);
		List<JsonNode> received = events.events();
		assertThat(received).extracting(event -> event.at("/data/interviewEvents/type").asString())
				.startsWith("ANSWER_EVALUATED", "QUESTION_READY");
		assertThat(received.get(0).at("/data/interviewEvents/answer/evaluation/score").asInt())
				.isEqualTo(cannedScore());
		assertThat(received.get(1).at("/data/interviewEvents/nextQuestion/order").asInt()).isEqualTo(2);

		JsonNode ended = graphQl.execute("""
				mutation ($input: EndInterviewInput!) {
				  endInterview(input: $input) { session { status report { overallScore } } errors { code } }
				}
				""", Map.of("input", Map.of("sessionId", session)));
		assertThat(ended.at("/data/endInterview/errors").size()).as(ended.toString()).isZero();
		assertThat(ended.at("/data/endInterview/session/status").asString()).isEqualTo("ABANDONED");
		endedSession = session;
		assertThat(ended.at("/data/endInterview/session/report/overallScore").asDouble()).isPositive();

		// Every model call is interview-service's, recorded in its own schema; the analysis was loaded, not run.
		Map<String, Integer> calls = modelCalls("interview", UUID.fromString(session));
		assertThat(calls).containsEntry("PLAN", 1).containsEntry("EVALUATION", 1).containsEntry("REPORT", 1);
		assertThat(calls.getOrDefault("QUESTION", 0) + calls.getOrDefault("FOLLOW_UP", 0))
				.as("the first question and the next one").isEqualTo(2);
		assertThat(count("SELECT count(*) FROM analysis.llm_call")).isZero();
	}

	/**
	 * Task 4.3: the answer of {@link #anInterviewRunsAcrossTheThreeApps}, sent with a W3C traceparent, is one trace in
	 * Jaeger across the three apps: the gateway's request, interview-service saving the answer, the outbox sends and
	 * the Kafka workers, the model calls, analysis-service's code lookups, and the gateway's fan-out of the events.
	 */
	@Test
	@Order(3)
	void theAnswerIsOneTraceAcrossTheThreeApps() throws Exception {
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
			List<String> spans = spansOf(answerTraceId);
			assertThat(spans).as(String.join("\n", spans)).contains(
					"graphql-gateway: graphql field submitAnswer",
					"interview-service: http post /internal/questions/{id}/answer",
					"interview-service: outbox AnswerSubmitted",
					"interview-service: interview.answer.submitted process",            // the evaluation worker
					"analysis-service: http post /internal/projects/{id}/chunks/ranges", // the code to grade against
					"interview-service: llm EVALUATION",
					"interview-service: outbox AnswerEvaluated",
					"interview-service: interview.answer.evaluated process",            // the orchestrator
					"interview-service: outbox QuestionReady",
					"graphql-gateway: interview.question.ready process");                // the subscriptions' fan-out
		});
	}

	/**
	 * Task 4.3: after the interview, each app serves its metrics to Prometheus: interview-service the model calls'
	 * latency histogram and tokens per purpose and its workers' lag per partition, every app its consumers' lag. Every
	 * metric the Grafana dashboard queries is among them, so the dashboard cannot drift from the code. And these
	 * scrapes, like the readiness probes at the start, made no traces.
	 */
	@Test
	@Order(4)
	void eachAppServesTheMetricsTheDashboardQueries() throws Exception {
		String analysis = get(apps.analysisService().url() + "/actuator/prometheus");
		String gateway = get(apps.gatewayUrl() + "/actuator/prometheus");
		// A consumer's lag per partition exists once it has fetched records there, and Micrometer looks for a Kafka
		// client's new metrics every 60 s: the interview's workers read records, so it appears within a minute.
		String interview = await().atMost(Duration.ofSeconds(75)).pollInterval(Duration.ofSeconds(2))
				.until(() -> get(apps.interviewService().url() + "/actuator/prometheus"),
						metrics -> metrics.contains("kafka_consumer_fetch_manager_records_lag{"));

		assertThat(interview).contains("ri_llm_call_seconds_bucket{")
				.containsPattern("ri_llm_tokens_total\\{[^}]*purpose=\"EVALUATION\"");
		assertThat(List.of(interview, analysis, gateway))   // each consumer's own lag, from its start
				.allSatisfy(metrics -> assertThat(metrics).contains("kafka_consumer_fetch_manager_records_lag_max{"));
		Set<String> queried = dashboardMetrics();
		String scraped = interview + analysis + gateway;
		assertThat(queried).isNotEmpty().allSatisfy(metric -> assertThat(scraped).as("queried by the dashboard")
				.containsPattern("(?m)^" + metric + "[{ ]"));
		for (String service : List.of("graphql-gateway", "analysis-service", "interview-service")) {
			List<String> operations = new ArrayList<>();   // Jaeger 2's v3 API: {"operations": [{"name": ...}]}
			JsonMapper.builder().build().readTree(get(apps.jaegerUrl() + "/api/v3/operations?service=" + service))
					.path("operations").forEach(operation -> operations.add(operation.path("name").asString()));
			assertThat(operations).as(service).anyMatch(name -> name.startsWith("http "))
					.noneMatch(name -> name.contains("actuator"));
		}
	}

	/** Each service owns the tables of its schema, and the gateway made none (it has no database). */
	@Test
	@Order(5)
	void eachServiceOwnsItsSchemaAndTheGatewayHasNone() throws Exception {
		assertThat(count("""
				SELECT count(*) FROM pg_tables WHERE schemaname = 'analysis' AND tableowner <> 'analysis_svc'
				""")).isZero();
		assertThat(count("""
				SELECT count(*) FROM pg_tables WHERE schemaname = 'interview' AND tableowner <> 'interview_svc'
				""")).isZero();
		assertThat(count("SELECT count(*) FROM pg_tables WHERE schemaname = 'interview'")).isPositive();
		assertThat(count("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")).isZero();
	}

	/**
	 * Task 4.2 acceptance criterion: analysis-service stopped. Starting an interview fails within seconds with a clear
	 * SERVICE_UNAVAILABLE, raised by interview-service's client and passed on as a problem detail, and the gateway's
	 * own call for projects fails the same way; what interview-service holds is still served.
	 */
	@Test
	@Order(6)
	void withAnalysisServiceDownAStartFailsFastWithAClearError() throws Exception {
		apps.analysisService().stop();

		Instant before = Instant.now();
		JsonNode started = graphQl.execute("""
				mutation ($input: StartInterviewInput!) {
				  startInterview(input: $input) { session { id } errors { code message } }
				}
				""", Map.of("input", Map.of("projectId", project.toString())));
		Duration took = Duration.between(before, Instant.now());

		assertThat(started.at("/data/startInterview/errors/0/code").asString()).as(started.toString())
				.isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(started.at("/data/startInterview/errors/0/message").asString()).isEqualTo(ANALYSIS_UNAVAILABLE);
		assertThat(started.at("/data/startInterview/session").isNull()).isTrue();
		assertThat(took).isLessThan(Duration.ofSeconds(3));
		JsonNode projects = graphQl.execute("{ projects(first: 1) { edges { node { id } } } }", Map.of());
		assertThat(projects.at("/errors/0/extensions/code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(projects.at("/errors/0/message").asString()).isEqualTo(ANALYSIS_UNAVAILABLE);
		JsonNode session = graphQl.execute("query ($id: ID!) { interviewSession(id: $id) { status } }",
				Map.of("id", endedSession));
		assertThat(session.at("/data/interviewSession/status").asString()).as(session.toString())
				.isEqualTo("ABANDONED");
	}

	/** interview-service stopped too: the gateway still answers at once, with the same clear error for it. */
	@Test
	@Order(7)
	void withInterviewServiceDownTheGatewayStillAnswersAtOnce() throws Exception {
		apps.interviewService().stop();

		Instant before = Instant.now();
		JsonNode interview = graphQl.execute("query ($id: ID!) { interviewSession(id: $id) { id } }",
				Map.of("id", UUID.randomUUID().toString()));
		Duration took = Duration.between(before, Instant.now());

		assertThat(interview.at("/errors/0/extensions/code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
		assertThat(interview.at("/errors/0/message").asString())
				.isEqualTo("The interview service is not available right now. Try again in a minute.");
		assertThat(took).isLessThan(Duration.ofSeconds(5));
	}

	/** @return each span of the trace in Jaeger as "service: operation", empty while Jaeger has none of it */
	private List<String> spansOf(String traceId) throws Exception {
		JsonNode trace = JsonMapper.builder().build().readTree(get(apps.jaegerUrl() + "/api/traces/" + traceId))
				.at("/data/0");
		List<String> spans = new ArrayList<>();
		for (JsonNode span : trace.path("spans")) {
			String service = trace.at("/processes/" + span.get("processID").asString() + "/serviceName").asString();
			spans.add(service + ": " + span.get("operationName").asString());
		}
		return spans;
	}

	/** The metric names in the Grafana dashboard's queries (observability/grafana/dashboards, task 4.3). */
	private static Set<String> dashboardMetrics() throws Exception {
		Path dashboard = Path.of(System.getProperty("system.root", ".."),
				"observability/grafana/dashboards/repo-interviewer.json");
		Set<String> metrics = new TreeSet<>();
		for (JsonNode panel : JsonMapper.builder().build().readTree(Files.readString(dashboard)).path("panels")) {
			for (JsonNode target : panel.path("targets")) {
				Matcher metric = Pattern.compile("\\b(?:ri|kafka|http|jvm|process)_[a-z0-9_]+")
						.matcher(target.path("expr").asString());
				while (metric.find()) {
					metrics.add(metric.group());
				}
			}
		}
		return metrics;
	}

	private static String get(String url) throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
					.body();
		}
	}

	private static int cannedScore() {
		return ((EvaluationOutput) CannedLlmOutputs.of(LlmPurpose.EVALUATION)).score();
	}

	/** Successful model calls of one session, per purpose, from a service's own {@code llm_call} table. */
	private Map<String, Integer> modelCalls(String schema, UUID session) throws SQLException {
		Map<String, Integer> calls = new HashMap<>();
		try (Connection db = admin(); PreparedStatement sql = db.prepareStatement("SELECT purpose, count(*) FROM "
				+ schema + ".llm_call WHERE success AND session_id = ? GROUP BY purpose")) {
			sql.setObject(1, session);
			try (ResultSet rows = sql.executeQuery()) {
				while (rows.next()) {
					calls.put(rows.getString(1), rows.getInt(2));
				}
			}
		}
		return calls;
	}

	private int count(String query) throws SQLException {
		try (Connection db = admin(); Statement sql = db.createStatement(); ResultSet row = sql.executeQuery(query)) {
			row.next();
			return row.getInt(1);
		}
	}

	/** The database's superuser: only the test reads across the schemas. */
	private Connection admin() throws SQLException {
		return DriverManager.getConnection(apps.adminJdbcUrl(), apps.adminUser(), apps.adminPassword());
	}

}
