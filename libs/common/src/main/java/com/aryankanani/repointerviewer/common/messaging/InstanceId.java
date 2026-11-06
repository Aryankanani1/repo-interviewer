package com.aryankanani.repointerviewer.common.messaging;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * This app instance's id, new at every start (task 2.6). Broadcast listeners put it in their consumer group, so every
 * instance has its own group and reads every event: a group shares a topic's partitions among its members, which
 * would give each event to only one instance. Workers keep one shared group per stage, so each event is handled once.
 */
@Component
@Profile("kafka")
public class InstanceId {

	private static final Logger log = LoggerFactory.getLogger(InstanceId.class);

	private final String value = UUID.randomUUID().toString();

	InstanceId() {
		log.info("Kafka instance id {}", value);
	}

	public String value() {
		return value;
	}

}
