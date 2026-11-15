package com.aryankanani.repointerviewer.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.KafkaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Task 2.1 acceptance test: with the {@code kafka} profile, the app starts against a real broker (Testcontainers) and
 * {@link KafkaAdmin} has created every topic of LLD §13.3, with 3 partitions and one copy each. The broker does not
 * auto-create topics (docker-compose.yml), so they exist only because {@link KafkaTopicConfig} declares them.
 */
@KafkaIntegrationTest
class KafkaTopicsTest {

	private final KafkaAdmin kafkaAdmin;

	KafkaTopicsTest(@Autowired KafkaAdmin kafkaAdmin) {
		this.kafkaAdmin = kafkaAdmin;
	}

	/** And each has its dead-letter topic, the same shape (task 2.4). */
	@Test
	void everyTopicExistsWithThreePartitionsAndOneCopy() {
		List<String> names = Topics.ALL.stream().flatMap(name -> Stream.of(name, Topics.dlt(name))).toList();
		Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(names.toArray(String[]::new));

		assertThat(topics).containsOnlyKeys(names);
		assertThat(topics).containsKey("project.embedded-dlt");
		assertThat(topics.values()).allSatisfy(topic -> {
			assertThat(topic.partitions()).hasSize(KafkaTopicConfig.PARTITIONS);
			assertThat(topic.partitions()).allSatisfy(
					partition -> assertThat(partition.replicas()).hasSize(KafkaTopicConfig.REPLICAS));
		});
	}

}
