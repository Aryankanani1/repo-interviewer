package com.aryankanani.repointerviewer.common.messaging;

import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares the {@link Topics} (task 2.1). At startup Spring's {@link KafkaAdmin} creates the missing ones; the broker
 * does not auto-create topics (docker-compose.yml), so a topic exists only because it is declared here.
 *
 * <p>Only with the {@code kafka} profile ({@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,kafka}).
 * Without it nothing declares a topic, so nothing connects to Kafka and the Phase 1 app and tests run without it.
 * With it, {@code spring.kafka.admin.fail-fast} stops the startup when the broker is unreachable.
 */
@Configuration
@Profile("kafka")
class KafkaTopicConfig {

	/** 3 lets three consumers of one group share a topic. Order holds per key, which is per partition. */
	static final int PARTITIONS = 3;

	/** One local broker, so one copy. A multi-broker cluster (Phase 3) needs more for safety. */
	static final int REPLICAS = 1;

	/**
	 * Every topic and its dead-letter topic (task 2.4). A DLT has the same partition count, because an event goes to
	 * the DLT partition with its original number; without its DLT, a failed event could never be set aside.
	 */
	@Bean
	KafkaAdmin.NewTopics topics() {
		return new KafkaAdmin.NewTopics(Topics.ALL.stream()
				.flatMap(name -> Stream.of(name, Topics.dlt(name)))
				.map(name -> TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build())
				.toArray(NewTopic[]::new));
	}

}
