package com.aryankanani.repointerviewer.common.messaging;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends outbox events to Kafka (task 2.2, LLD §13.1). Every {@code app.outbox.poll-interval}: one transaction locks
 * up to {@code app.outbox.batch-size} unpublished rows ({@code FOR UPDATE SKIP LOCKED}), sends them one by one in
 * creation order, waits for each ack, and sets {@code published_at}.
 *
 * <ul>
 * <li><b>At-least-once.</b> A crash after Kafka's ack but before the commit sends the event again on the next poll.
 * Consumers skip duplicates by {@code eventId} (task 2.3).</li>
 * <li><b>Order per aggregate.</b> The first failed send ends the batch, so a later event never overtakes an earlier
 * one; the rest go out, in order, on a later poll.</li>
 * <li><b>Network inside a transaction, on purpose</b> (DECISIONS.md D-017): the row locks are what keep two relays
 * from sending the same row at once, so they must last until the ack. Only outbox rows are locked, and the producer
 * timeouts in application.yml cap a send at 10 s.</li>
 * </ul>
 */
@Component
@Profile("kafka")
class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	/** A last guard, above {@code delivery.timeout.ms} (10 s), so Kafka's own limit normally ends a send first. */
	private static final long SEND_TIMEOUT_SECONDS = 15;

	private final OutboxEventRepository events;

	private final KafkaOperations<String, String> kafka;

	private final JsonMapper json;

	private final TransactionTemplate transaction;

	private final int batchSize;

	OutboxRelay(OutboxEventRepository events, KafkaOperations<String, String> kafka, JsonMapper json,
			PlatformTransactionManager transactionManager, AppProperties properties) {
		this.events = events;
		this.kafka = kafka;
		this.json = json;
		this.transaction = new TransactionTemplate(transactionManager);
		this.batchSize = properties.outbox().batchSize();
	}

	/** {@code fixedDelay}: the next poll starts an interval after this one ended, so polls never overlap. */
	@Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
	void poll() {
		publishBatch();
	}

	/** One poll. @return the ids published, in the order they were sent */
	List<UUID> publishBatch() {
		return transaction.execute(status -> {
			List<UUID> published = new ArrayList<>();
			for (OutboxEvent event : events.lockUnpublished(batchSize)) {
				if (!send(event)) {
					break;
				}
				event.published(Instant.now());
				published.add(event.getId());
			}
			if (!published.isEmpty()) {
				log.debug("Outbox: published {} events", published.size());
			}
			return published;
		});
	}

	/** Sends one event and waits for Kafka's ack. @return false when it failed; the event stays unpublished */
	private boolean send(OutboxEvent event) {
		String envelope = json.writeValueAsString(new EventEnvelope(event.getId(), event.getType(), event.getVersion(),
				event.getCreatedAt(), event.getAggregateId(), json.readTree(event.getPayload())));
		try {
			kafka.send(event.getTopic(), event.getAggregateId().toString(), envelope)
					.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return true;
		}
		catch (ExecutionException | TimeoutException | RuntimeException ex) {   // the try holds only the send
			log.warn("Outbox: event {} to {} not sent, retried on the next poll: {}", event.getId(), event.getTopic(),
					ex.toString());
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();   // shutting down: stop, the event stays unpublished
			return false;
		}
	}

}
