package com.aryankanani.repointerviewer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link IntegrationTest} with the {@code kafka} profile and a real broker ({@link KafkaContainerConfiguration}). Every
 * Kafka test uses this one annotation, so Spring caches a single Kafka context: one broker, one more Postgres.
 *
 * <p>The outbox relay's timer is set to an hour, so it does not race the tests: they call
 * {@code OutboxRelay.publishBatch()} themselves and know exactly when a poll happens. {@code OutboxRelayTest} checks
 * that the timer itself is wired.
 *
 * <p>Consumer retries wait 100 ms, 200 ms, 400 ms instead of 1 s, 2 s, 4 s, and a {@link RecordingRetryListener}
 * records what the error handler did (task 2.4).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@ActiveProfiles({ "test", "kafka" })
@Import({ KafkaContainerConfiguration.class, RecordingRetryListener.class })
@TestPropertySource(properties = { "app.outbox.poll-interval=1h", "app.consumer-retry.initial-backoff=100ms" })
public @interface KafkaIntegrationTest {
}
