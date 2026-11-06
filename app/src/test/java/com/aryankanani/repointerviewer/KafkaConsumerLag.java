package com.aryankanani.repointerviewer;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * For Kafka tests: "nothing more happened" is proven by waiting until each consumer group has committed an offset at
 * the end of every partition of its topic, so every event sent so far was read and handled (tasks 2.3 and 2.5).
 */
public final class KafkaConsumerLag {

	private KafkaConsumerLag() {
	}

	/** @param topicsByGroup each consumer group and the topic it reads (3 partitions, as every topic here) */
	public static void awaitCaughtUp(KafkaAdmin kafkaAdmin, Map<String, String> topicsByGroup) {
		Object bootstrap = kafkaAdmin.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
		try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
			await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
				for (Map.Entry<String, String> group : topicsByGroup.entrySet()) {
					Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group.getKey())
							.partitionsToOffsetAndMetadata().get();
					List<TopicPartition> partitions = IntStream.range(0, 3)
							.mapToObj(partition -> new TopicPartition(group.getValue(), partition)).toList();
					var ends = admin.listOffsets(partitions.stream()
							.collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest()))).all().get();
					for (TopicPartition partition : partitions) {
						long end = ends.get(partition).offset();
						OffsetAndMetadata offset = committed.get(partition);
						if (end > 0 && (offset == null || offset.offset() < end)) {
							return false;
						}
					}
				}
				return true;
			});
		}
	}

}
