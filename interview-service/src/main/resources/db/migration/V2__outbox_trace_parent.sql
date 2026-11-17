-- Task 4.3 (DECISIONS.md D-028): the W3C traceparent of the span that wrote the event, 00-<trace id>-<span id>-<flags>
-- (55 characters). The relay sends the event in a span that continues it, so the Kafka record carries the trace to
-- its consumer. NULL for an event written outside any trace, and for the rows written before this column.
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55);
