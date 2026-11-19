package com.aryankanani.repointerviewer.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeLlmGateway;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.contracts.interview.AnswerFlowEvent;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.outbox.OutboxPump;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task 4.3: an answer's trace survives the outbox and both Kafka hops. The test plays the incoming request: it submits
 * the answer inside a span of its own. Each event of the flow is written inside the span that handled the one before
 * it and keeps that span's traceparent ({@code OutboxTracing}), so all three carry the request's trace id, each from a
 * different span: AnswerSubmitted from the request, AnswerEvaluated from the evaluation worker's record, QuestionReady
 * from the orchestrator's. The workers could only have continued the trace from the records' headers.
 *
 * <p>Tests keep a real tracer and only stop the export (Boot's test default), so the ids are real.
 */
@KafkaIntegrationTest
class AnswerTraceTest {

	private final InterviewApi interviews;

	private final FakeAnalysisApi fixture;

	private final FakeLlmGateway llm;

	private final Tracer tracer;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	AnswerTraceTest(@Autowired InterviewApi interviews, @Autowired FakeAnalysisApi fixture,
			@Autowired FakeLlmGateway llm, @Autowired Tracer tracer, @Autowired JdbcTemplate jdbc,
			@Autowired ApplicationContext context) {
		this.interviews = interviews;
		this.fixture = fixture;
		this.llm = llm;
		this.tracer = tracer;
		this.jdbc = jdbc;
		this.context = context;
	}

	@Test
	void anAnswerIsOneTraceFromTheRequestThroughBothWorkers() {
		jdbc.update("DELETE FROM interview_session");
		jdbc.update("DELETE FROM outbox_event");
		jdbc.update("DELETE FROM processed_event");
		fixture.reset();
		llm.reset();
		StartResponse started = interviews.start(new StartInterviewRequest(TestUsers.CANDIDATE,
				fixture.readyProject(), null, null, 3));
		UUID session = started.session().id();

		Span request = tracer.nextSpan().name("submitAnswer").start();
		try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
			interviews.submitAnswer(started.firstQuestion().id(), new AnswerRequest("Both read the same refundedAmount."));
		}
		finally {
			request.end();
		}
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			OutboxPump.publishPending(context);
			return traceParents(session).containsKey(AnswerFlowEvent.QUESTION_READY);
		});

		Map<String, String> traceParents = traceParents(session);
		String submitted = traceParents.get(AnswerFlowEvent.ANSWER_SUBMITTED);
		String evaluated = traceParents.get(AnswerFlowEvent.ANSWER_EVALUATED);
		String questionReady = traceParents.get(AnswerFlowEvent.QUESTION_READY);
		assertThat(traceId(submitted)).isEqualTo(request.context().traceId());
		assertThat(traceId(evaluated)).isEqualTo(request.context().traceId());
		assertThat(traceId(questionReady)).isEqualTo(request.context().traceId());
		assertThat(spanId(submitted)).isEqualTo(request.context().spanId());
		assertThat(spanId(evaluated)).isNotIn(spanId(submitted));
		assertThat(spanId(questionReady)).isNotIn(spanId(submitted), spanId(evaluated));
	}

	/** Each event type of the session and the traceparent it was written with. */
	private Map<String, String> traceParents(UUID session) {
		Map<String, String> byType = new HashMap<>();
		jdbc.query("SELECT type, trace_parent FROM outbox_event WHERE aggregate_id = ?",
				row -> {
					byType.put(row.getString("type"), row.getString("trace_parent"));
				}, session);
		return byType;
	}

	/** {@code 00-<trace id>-<span id>-<flags>} */
	private static String traceId(String traceParent) {
		return traceParent.split("-")[1];
	}

	private static String spanId(String traceParent) {
		return traceParent.split("-")[2];
	}

}
