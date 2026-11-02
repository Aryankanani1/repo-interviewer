package com.aryankanani.repointerviewer.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One event waiting to be sent, or already sent (table {@code outbox_event}, V3). Written by {@link OutboxWriter} in
 * the caller's transaction, sent by {@link OutboxRelay}. Package-private: other modules only see the writer.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEvent {

	/** The payload schema version every event has so far. */
	static final short FIRST_VERSION = 1;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Enumerated(EnumType.STRING)
	private AggregateType aggregateType;

	private UUID aggregateId;

	private String topic;

	private String type;

	private short version;

	/** JSON text, stored as JSONB. */
	@JdbcTypeCode(SqlTypes.JSON)
	private String payload;

	@CreationTimestamp
	@Column(updatable = false)
	private Instant createdAt;

	private Instant publishedAt;

	/** For JPA only. */
	protected OutboxEvent() {
	}

	OutboxEvent(String topic, AggregateType aggregateType, UUID aggregateId, String type, String payload) {
		this.topic = topic;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.type = type;
		this.version = FIRST_VERSION;
		this.payload = payload;
	}

	/** Kafka acknowledged it. */
	void published(Instant at) {
		this.publishedAt = at;
	}

	UUID getId() {
		return id;
	}

	UUID getAggregateId() {
		return aggregateId;
	}

	String getTopic() {
		return topic;
	}

	String getType() {
		return type;
	}

	short getVersion() {
		return version;
	}

	String getPayload() {
		return payload;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getPublishedAt() {
		return publishedAt;
	}

}
