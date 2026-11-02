package com.aryankanani.repointerviewer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Kafka broker for tests that run with the {@code kafka} profile (Phase 2). {@code @ServiceConnection} points
 * {@code spring.kafka.bootstrap-servers} at the container ({@code ApacheKafkaContainerConnectionDetailsFactory} in
 * spring-boot-kafka). Import it next to {@link IntegrationTest}; it adds a context of its own, with its own Postgres.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

	/** Same image as docker-compose.yml, and the same version as the kafka-clients library Boot manages. */
	static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.2.2");

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(KAFKA_IMAGE);
	}

}
