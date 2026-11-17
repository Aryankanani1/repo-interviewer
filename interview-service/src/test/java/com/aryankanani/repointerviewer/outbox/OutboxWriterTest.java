package com.aryankanani.repointerviewer.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.common.messaging.AggregateType;
import com.aryankanani.repointerviewer.common.messaging.Topics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 2.2: {@link OutboxWriter} is plain database work, so it runs in the normal test context, with no broker. The
 * relay is not there without the {@code kafka} profile.
 */
@IntegrationTest
class OutboxWriterTest {

	private final OutboxWriter writer;

	private final TransactionTemplate transaction;

	private final JdbcTemplate jdbc;

	private final ApplicationContext context;

	private final Tracer tracer;

	OutboxWriterTest(@Autowired OutboxWriter writer, @Autowired PlatformTransactionManager transactionManager,
			@Autowired JdbcTemplate jdbc, @Autowired ApplicationContext context, @Autowired Tracer tracer) {
		this.writer = writer;
		this.transaction = new TransactionTemplate(transactionManager);
		this.jdbc = jdbc;
		this.context = context;
		this.tracer = tracer;
	}

	@BeforeEach
	void emptyOutbox() {
		jdbc.update("DELETE FROM outbox_event");
	}

	@Test
	void anEventIsARowWithItsPayloadAsJson() {
		UUID aggregate = UUID.randomUUID();

		UUID id = transaction.execute(status -> writer.write(Topics.ANALYSIS_REQUESTED, AggregateType.PROJECT,
				aggregate, "AnalysisRequested", Map.of("projectId", aggregate, "attempt", 1)));

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT topic, aggregate_type, aggregate_id, type, version, published_at,
				       payload->>'projectId' AS project_id, (payload->>'attempt')::int AS attempt
				FROM outbox_event WHERE id = ?
				""", id);
		assertThat(row).containsEntry("topic", Topics.ANALYSIS_REQUESTED).containsEntry("aggregate_type", "PROJECT")
				.containsEntry("aggregate_id", aggregate).containsEntry("type", "AnalysisRequested")
				.containsEntry("version", 1).containsEntry("published_at", null)
				// a JSON object, not a quoted string: its fields can be read in SQL
				.containsEntry("project_id", aggregate.toString()).containsEntry("attempt", 1);
	}

	/**
	 * Task 4.3: an event keeps the W3C traceparent of the span it was written in (the request's, or the Kafka
	 * record's), so the relay can send it in the same trace. Outside any span there is nothing to keep.
	 */
	@Test
	void anEventKeepsTheTraceparentOfTheSpanItWasWrittenIn() {
		Span request = tracer.nextSpan().name("request").start();
		UUID traced;
		try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
			traced = transaction.execute(status -> writer.write(Topics.ANALYSIS_REQUESTED, AggregateType.PROJECT,
					UUID.randomUUID(), "AnalysisRequested", Map.of()));
		}
		finally {
			request.end();
		}
		UUID untraced = transaction.execute(status -> writer.write(Topics.ANALYSIS_REQUESTED, AggregateType.PROJECT,
				UUID.randomUUID(), "AnalysisRequested", Map.of()));

		assertThat(traceParent(traced)).hasSize(55)   // 00-<trace id>-<span id>-<flags>
				.startsWith("00-" + request.context().traceId() + "-" + request.context().spanId() + "-");
		assertThat(traceParent(untraced)).isNull();
	}

	/** {@code MANDATORY}: without the caller's transaction there would be no state change to commit it with. */
	@Test
	void writingOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> writer.write(Topics.ANALYSIS_REQUESTED, AggregateType.PROJECT, UUID.randomUUID(),
				"AnalysisRequested", Map.of())).isInstanceOf(IllegalTransactionStateException.class);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
	}

	@Test
	void anUndeclaredTopicIsRefused() {
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> writer.write("project.analysis.requestd",
				AggregateType.PROJECT, UUID.randomUUID(), "AnalysisRequested", Map.of())))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Topics");
	}

	@Test
	void withoutTheKafkaProfileNothingSendsTheOutbox() {
		assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
	}

	private String traceParent(UUID event) {
		return jdbc.queryForObject("SELECT trace_parent FROM outbox_event WHERE id = ?", String.class, event);
	}

}
