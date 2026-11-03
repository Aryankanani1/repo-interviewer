package com.aryankanani.repointerviewer;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collection;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * For Kafka tests (task 2.6): waits until a listener has its partitions. A group that starts at the newest event
 * ({@code auto.offset.reset=latest}) skips what was sent before it joined, so a test must not send earlier.
 */
public final class KafkaListeners {

	private KafkaListeners() {
	}

	public static void awaitAssigned(ApplicationContext context, String listenerId, int partitions) {
		MessageListenerContainer container = context.getBean(KafkaListenerEndpointRegistry.class)
				.getListenerContainer(listenerId);
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> {
			Collection<TopicPartition> assigned = container.getAssignedPartitions();
			return assigned != null && assigned.size() == partitions;
		});
	}

}
