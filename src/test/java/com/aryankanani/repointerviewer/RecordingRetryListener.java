package com.aryankanani.repointerviewer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RetryListener;

/**
 * Records what Kafka's error handler did (task 2.4): every failed delivery with its attempt number, and every record
 * it sent to a dead-letter topic. In every {@link KafkaIntegrationTest} context; call {@link #reset} in
 * {@code @BeforeEach}.
 */
public class RecordingRetryListener implements RetryListener {

	private record Failure(String topic, String key, int attempt) {
	}

	private final List<Failure> failures = new CopyOnWriteArrayList<>();

	private final List<Failure> recovered = new CopyOnWriteArrayList<>();

	public void reset() {
		failures.clear();
		recovered.clear();
	}

	@Override
	public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
		failures.add(new Failure(record.topic(), String.valueOf(record.key()), deliveryAttempt));
	}

	@Override
	public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
		recovered.add(new Failure(record.topic(), String.valueOf(record.key()), 0));
	}

	/** The attempt numbers of the failed deliveries of this key's records on {@code topic}, in order. */
	public List<Integer> failedAttempts(String topic, Object key) {
		return failures.stream().filter(failure -> matches(failure, topic, key)).map(Failure::attempt).toList();
	}

	/** How many of this key's records on {@code topic} were sent to the dead-letter topic. */
	public long deadLettered(String topic, Object key) {
		return recovered.stream().filter(failure -> matches(failure, topic, key)).count();
	}

	private static boolean matches(Failure failure, String topic, Object key) {
		return failure.topic().equals(topic) && failure.key().equals(Objects.toString(key));
	}

}
