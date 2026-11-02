-- V3: transactional outbox (task 2.2, LLD §13.1).
-- An event is a row written in the same transaction as the state change it describes, so both commit or neither
-- does. OutboxRelay sends unpublished rows to Kafka and sets published_at: at-least-once delivery, so consumers
-- record what they processed in processed_event (task 2.3).

CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,                    -- UUIDv7 from Java; also the envelope's eventId
    aggregate_type VARCHAR(30) NOT NULL,                -- PROJECT | SESSION
    aggregate_id   UUID        NOT NULL,                -- also the Kafka key: one aggregate, one partition, in order
    topic          VARCHAR(80) NOT NULL,
    type           VARCHAR(60) NOT NULL,
    version        SMALLINT    NOT NULL DEFAULT 1,      -- payload schema version, sent in the envelope
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,                         -- NULL until the relay got Kafka's ack
    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('PROJECT', 'SESSION'))
);

-- Serves the relay's poll: unpublished rows, oldest first. Partial, so it holds only rows still to send and stays
-- small however many events were published.
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- Consumers insert (event id, consumer name) in the same transaction as their state change; a duplicate key means
-- the event was already handled (task 2.3).
CREATE TABLE processed_event (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
