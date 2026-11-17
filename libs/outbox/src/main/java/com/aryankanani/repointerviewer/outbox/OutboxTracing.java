package com.aryankanani.repointerviewer.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Keeps a trace going across the outbox (task 4.3, DECISIONS.md D-028). An event is written in one thread, inside the
 * span of the request or the Kafka record that caused it, and sent later by {@link OutboxRelay} on its own thread,
 * where that span is long gone. So {@link OutboxWriter} saves the span's W3C {@code traceparent} with the event
 * ({@code 00-<trace id>-<span id>-<flags>}), and the relay sends it inside a span that continues it. The Kafka
 * template's observation then puts a {@code traceparent} header on the record, and the consumer's observation reads it:
 * one trace from the GraphQL request to the last worker.
 */
@Component
class OutboxTracing {

	static final String TRACEPARENT = "traceparent";

	private final Tracer tracer;

	private final Propagator propagator;

	OutboxTracing(Tracer tracer, Propagator propagator) {
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/** @return the current span's {@code traceparent}, or null outside any trace */
	String currentTraceParent() {
		TraceContext context = tracer.currentTraceContext().context();
		if (context == null) {
			return null;
		}
		Map<String, String> headers = new HashMap<>();
		propagator.inject(context, headers, Map::put);
		return headers.get(TRACEPARENT);
	}

	/**
	 * The span the relay sends {@code event} in: a child of the span that wrote it, or the start of a new trace for an
	 * event written outside any. Named after the event, so a trace shows what went through Kafka.
	 */
	Span sendSpan(OutboxEvent event) {
		Span.Builder span = event.getTraceParent() == null ? tracer.spanBuilder()
				: propagator.extract(Map.of(TRACEPARENT, event.getTraceParent()), Map::get);
		return span.name("outbox " + event.getType()).tag("messaging.destination.name", event.getTopic()).start();
	}

	Tracer.SpanInScope inScope(Span span) {
		return tracer.withSpan(span);
	}

}
