package com.aryankanani.repointerviewer.common.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The value of every Kafka message (LLD §13.2), as JSON. The key is {@code aggregateId}. Consumers use
 * {@code eventId} to skip an event they already handled: delivery is at-least-once (task 2.3).
 *
 * @param version the payload's schema version; a consumer that does not know it must not guess
 */
public record EventEnvelope(UUID eventId, String type, int version, Instant occurredAt, UUID aggregateId,
		JsonNode payload) {
}
