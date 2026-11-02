package com.aryankanani.repointerviewer.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.KafkaContainerConfiguration;
import java.util.Map;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.ActiveProfiles;

/**
 * Task 2.1 acceptance test: with the {@code kafka} profile, the app starts against a real broker (Testcontainers) and
 * {@link KafkaAdmin} has created every topic of LLD §13.3, with 3 partitions and one copy each. The broker does not
 * auto-create topics (docker-compose.yml), so they exist only because {@link KafkaTopicConfig} declares them.
 */
@IntegrationTest
@ActiveProfiles({ "test", "kafka" })
@Import(KafkaContainerConfiguration.class)
class KafkaTopicsTest {

	private final KafkaAdmin kafkaAdmin;

	KafkaTopicsTest(@Autowired KafkaAdmin kafkaAdmin) {
		this.kafkaAdmin = kafkaAdmin;
	}

	@Test
	void everyTopicExistsWithThreePartitionsAndOneCopy() {
		Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(Topics.ALL.toArray(String[]::new));

		assertThat(topics).containsOnlyKeys(Topics.ALL);
		assertThat(topics.values()).allSatisfy(topic -> {
			assertThat(topic.partitions()).hasSize(KafkaTopicConfig.PARTITIONS);
			assertThat(topic.partitions()).allSatisfy(
					partition -> assertThat(partition.replicas()).hasSize(KafkaTopicConfig.REPLICAS));
		});
	}

}
