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
 * Kafka test uses this one annotation, so Spring caches a single Kafka context with one broker.
 *
 * <p>No outbox here (task 4.1 step 4a): tests send events to the broker themselves, as the services' relays do.
 * Consumer retries wait 100 ms, 200 ms, 400 ms instead of 1 s, 2 s, 4 s, and a {@link RecordingRetryListener}
 * records what the error handler did (task 2.4).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@IntegrationTest
@ActiveProfiles({ "test", "kafka" })
@Import({ KafkaContainerConfiguration.class, RecordingRetryListener.class })
@TestPropertySource(properties = "app.consumer-retry.initial-backoff=100ms")
public @interface KafkaIntegrationTest {
}
