package com.aryankanani.repointerviewer.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.aryankanani.repointerviewer.FixtureIndexer;
import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 2.2 acceptance tests: the outbox and {@link OutboxRelay} against a real broker (Testcontainers). The relay's
 * timer is slowed to an hour ({@link KafkaIntegrationTest}), so each test runs {@link OutboxRelay#publishBatch} itself.
 *
 * <p>"Nothing was published" cannot be seen by waiting. So after the event under test, a test writes a committed
 * <b>sentinel</b> event for the same aggregate: the same key, so the same partition, after it. Once the sentinel
 * arrives, every earlier event of that aggregate has arrived too, or never will.
 */
@KafkaIntegrationTest
class OutboxRelayTest {

	private static final String TOPIC = Topics.ANALYSIS_REQUESTED;

	private final OutboxRelay relay;

	private final OutboxWriter writer;

	private final OutboxEventRepository events;

	private final KafkaTemplate<String, String> kafkaTemplate;

	private final KafkaAdmin kafkaAdmin;

	private final JsonMapper json;

	private final PlatformTransactionManager transactionManager;

	private final TransactionTemplate transaction;

	private final AppProperties properties;

	private final FixtureIndexer fixture;

	private final JdbcTemplate jdbc;

	private final List<ScheduledTaskHolder> scheduled;

	OutboxRelayTest(@Autowired OutboxRelay relay, @Autowired OutboxWriter writer,
			@Autowired OutboxEventRepository events, @Autowired KafkaTemplate<String, String> kafkaTemplate,
			@Autowired KafkaAdmin kafkaAdmin, @Autowired JsonMapper json,
			@Autowired PlatformTransactionManager transactionManager, @Autowired AppProperties properties,
			@Autowired FixtureIndexer fixture, @Autowired JdbcTemplate jdbc,
			@Autowired List<ScheduledTaskHolder> scheduled) {
		this.relay = relay;
		this.writer = writer;
		this.events = events;
		this.kafkaTemplate = kafkaTemplate;
		this.kafkaAdmin = kafkaAdmin;
		this.json = json;
		this.transactionManager = transactionManager;
		this.transaction = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.fixture = fixture;
		this.jdbc = jdbc;
		this.scheduled = scheduled;
	}

	@BeforeEach
	void emptyTables() {
		jdbc.update("DELETE FROM outbox_event");
		jdbc.update("DELETE FROM project");
	}

	/** Acceptance criterion: state and outbox in one committed transaction → exactly one event. */
	@Test
	void aStateChangeCommittedWithItsEventPublishesExactlyOneEvent() {
		UUID[] written = transaction.execute(status -> {
			UUID project = fixture.newProject();
			return new UUID[] { project, writer.write(TOPIC, AggregateType.PROJECT, project, "AnalysisRequested",
					Map.of("projectId", project)) };
		});
		UUID project = written[0];
		UUID event = written[1];

		assertThat(relay.publishBatch()).containsExactly(event);
		UUID sentinel = writeCommitted(project);
		assertThat(relay.publishBatch()).as("the first event is not sent again").containsExactly(sentinel);

		List<EventEnvelope> received = receivedUntil(project, sentinel);
		assertThat(received).extracting(EventEnvelope::eventId).containsExactly(event, sentinel);
		EventEnvelope envelope = received.getFirst();
		assertThat(envelope.type()).isEqualTo("AnalysisRequested");
		assertThat(envelope.version()).isEqualTo(1);
		assertThat(envelope.aggregateId()).isEqualTo(project);
		assertThat(envelope.occurredAt()).isNotNull();
		assertThat(envelope.payload().get("projectId").asString()).isEqualTo(project.toString());
		assertThat(events.findById(event).orElseThrow().getPublishedAt()).isNotNull();
	}

	/** Acceptance criterion: a rolled-back transaction leaves neither the state nor the event, and sends nothing. */
	@Test
	void aRolledBackTransactionPublishesNothing() {
		AtomicReference<UUID> project = new AtomicReference<>();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			project.set(fixture.newProject());
			writer.write(TOPIC, AggregateType.PROJECT, project.get(), "AnalysisRequested", Map.of());
			throw new IllegalStateException("rolled back on purpose");
		})).hasMessage("rolled back on purpose");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM project WHERE id = ?", Integer.class, project.get())).isZero();
		assertThat(events.count()).isZero();
		UUID sentinel = writeCommitted(project.get());
		assertThat(relay.publishBatch()).containsExactly(sentinel);
		assertThat(receivedUntil(project.get(), sentinel)).extracting(EventEnvelope::eventId).containsExactly(sentinel);
	}

	/** Two relays share the work: a row another one holds is skipped, not waited for, and goes out later. */
	@Test
	void aRowLockedByAnotherRelayIsSkippedNotWaitedFor() throws Exception {
		UUID locked = writeCommitted(UUID.randomUUID());
		UUID free = writeCommitted(UUID.randomUUID());
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread otherRelay = Thread.ofVirtual().start(() -> transaction.executeWithoutResult(status -> {
			jdbc.queryForObject("SELECT id FROM outbox_event WHERE id = ? FOR UPDATE", UUID.class, locked);
			holding.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}));
		try {
			assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
			// Without SKIP LOCKED this call would wait for the other transaction: the timeout turns that into a failure.
			assertThat(assertTimeoutPreemptively(Duration.ofSeconds(10), relay::publishBatch)).containsExactly(free);
		}
		finally {
			release.countDown();
			otherRelay.join();
		}
		assertThat(relay.publishBatch()).containsExactly(locked);
	}

	@Test
	void oneAggregatesEventsArriveInTheOrderTheyWereWritten() {
		UUID aggregate = UUID.randomUUID();
		List<UUID> written = transaction.execute(status -> List.of(write(aggregate, "First"), write(aggregate, "Second"),
				write(aggregate, "Third")));

		assertThat(relay.publishBatch()).containsExactlyElementsOf(written);
		assertThat(receivedUntil(aggregate, written.getLast())).extracting(EventEnvelope::type)
				.containsExactly("First", "Second", "Third");
	}

	/** A failed send ends the batch: the later event does not overtake it, and both go out, in order, next time. */
	@Test
	void aFailedSendEndsTheBatchAndTheRestGoOutInOrderLater() {
		UUID aggregate = UUID.randomUUID();
		List<UUID> written = transaction.execute(status -> List.of(write(aggregate, "First"), write(aggregate, "Second"),
				write(aggregate, "Third")));
		KafkaTemplate<String, String> brokerDownForSecond = spy(kafkaTemplate);
		doReturn(CompletableFuture.failedFuture(new TimeoutException("broker down"))).when(brokerDownForSecond)
				.send(eq(TOPIC), eq(aggregate.toString()), argThat((String value) -> value.contains(written.get(1).toString())));
		OutboxRelay failingRelay = new OutboxRelay(events, brokerDownForSecond, json, transactionManager, properties);

		assertThat(failingRelay.publishBatch()).containsExactly(written.get(0));
		assertThat(events.findById(written.get(1)).orElseThrow().getPublishedAt()).isNull();
		assertThat(relay.publishBatch()).containsExactly(written.get(1), written.get(2));
		assertThat(receivedUntil(aggregate, written.getLast())).extracting(EventEnvelope::type)
				.containsExactly("First", "Second", "Third");
	}

	/** The timer the other tests slow down: wired, with app.outbox.poll-interval as its delay. */
	@Test
	void theRelayPollsOnAFixedDelay() {
		assertThat(scheduled.stream().flatMap(holder -> holder.getScheduledTasks().stream()))
				.filteredOn(task -> task.toString().endsWith("OutboxRelay.poll"))
				.singleElement()
				.satisfies(task -> assertThat(task.getTask()).isInstanceOfSatisfying(FixedDelayTask.class,
						delay -> assertThat(delay.getIntervalDuration()).isEqualTo(Duration.ofHours(1))));
	}

	private UUID writeCommitted(UUID aggregate) {
		return transaction.execute(status -> write(aggregate, "Sentinel"));
	}

	private UUID write(UUID aggregate, String type) {
		return writer.write(TOPIC, AggregateType.PROJECT, aggregate, type, Map.of("aggregateId", aggregate));
	}

	/**
	 * Reads the topic from the beginning with a consumer of its own (no group: it reads all partitions itself) until
	 * {@code lastEventId} arrives. @return the aggregate's envelopes, in the order Kafka holds them
	 */
	private List<EventEnvelope> receivedUntil(UUID aggregate, UUID lastEventId) {
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				kafkaAdmin.getConfigurationProperties().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		List<EventEnvelope> found = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
					.map(partition -> new TopicPartition(TOPIC, partition.partition())).toList();
			consumer.assign(partitions);
			consumer.seekToBeginning(partitions);
			// pollInSameThread: a KafkaConsumer must only be used by the thread that owns it.
			await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
					if (record.key().equals(aggregate.toString())) {
						found.add(json.readValue(record.value(), EventEnvelope.class));
					}
				}
				return found.stream().anyMatch(envelope -> envelope.eventId().equals(lastEventId));
			});
		}
		return found;
	}

}
