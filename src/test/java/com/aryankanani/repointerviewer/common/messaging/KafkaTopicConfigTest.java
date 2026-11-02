package com.aryankanani.repointerviewer.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Task 2.1: topics are declared only with the {@code kafka} profile, so the Phase 1 app and its tests never connect
 * to Kafka. No broker needed: {@link KafkaTopicsTest} checks the real topics.
 */
class KafkaTopicConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(KafkaTopicConfig.class);

	@Test
	void withoutTheKafkaProfileNoTopicIsDeclared() {
		contextRunner.withPropertyValues("spring.profiles.active=dev")
				.run(context -> assertThat(context).doesNotHaveBean(KafkaAdmin.NewTopics.class));
	}

	@Test
	void theKafkaProfileDeclaresTheTopics() {
		contextRunner.withPropertyValues("spring.profiles.active=dev,kafka")
				.run(context -> assertThat(context).hasSingleBean(KafkaAdmin.NewTopics.class));
	}

}
