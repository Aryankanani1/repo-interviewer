package com.aryankanani.repointerviewer.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.CannedLlmOutputs;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.EvaluationOutput;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * Task 4.1 step 4b: the three apps together, as deployed, driven only through the gateway's GraphQL over HTTP. Real
 * HTTP between the apps (AnalysisApi, InterviewApi), real Kafka between the interview workers and the gateway's
 * subscriptions, real Postgres with one user and schema per service. Fake only: the models ({@code FakeModelServer})
 * and the analysis of the fixture repository, which is loaded from its snapshot ({@link FixtureSeeder}).
 *
 * <p>About a minute: two containers and three app starts, once for the class. App output:
 * {@code system-test/target/system-*.log}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)   // the last test stops interview-service
class InterviewSystemTest {

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
				"text", "Both read the same refundedAmount, so both checks pass.")));

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
		assertThat(ended.at("/data/endInterview/session/report/overallScore").asDouble()).isPositive();

		// Every model call is interview-service's, recorded in its own schema; the analysis was loaded, not run.
		Map<String, Integer> calls = modelCalls("interview", UUID.fromString(session));
		assertThat(calls).containsEntry("PLAN", 1).containsEntry("EVALUATION", 1).containsEntry("REPORT", 1);
		assertThat(calls.getOrDefault("QUESTION", 0) + calls.getOrDefault("FOLLOW_UP", 0))
				.as("the first question and the next one").isEqualTo(2);
		assertThat(count("SELECT count(*) FROM analysis.llm_call")).isZero();
	}

	/** Each service owns the tables of its schema, and the gateway made none (it has no database). */
	@Test
	@Order(3)
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
	 * interview-service stopped: the gateway still answers at once, with a generic error for an interview and data
	 * for a project. A clear error and a circuit breaker are task 4.2.
	 */
	@Test
	@Order(4)
	void withInterviewServiceDownTheGatewayStillAnswersAtOnce() throws Exception {
		apps.interviewService().stop();

		Instant before = Instant.now();
		JsonNode interview = graphQl.execute("query ($id: ID!) { interviewSession(id: $id) { id } }",
				Map.of("id", UUID.randomUUID().toString()));
		Duration took = Duration.between(before, Instant.now());

		assertThat(interview.at("/errors/0/extensions/code").asString()).isEqualTo("INTERNAL");
		assertThat(took).isLessThan(Duration.ofSeconds(5));
		JsonNode projects = graphQl.execute("{ projects(first: 1) { edges { node { id } } } }", Map.of());
		assertThat(projects.has("errors")).as(projects.toString()).isFalse();
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
