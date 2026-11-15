package com.aryankanani.repointerviewer.common.messaging;

import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * For Kafka tests in other packages: one outbox relay pass on demand. The relay is package-private, and its timer is
 * slowed to an hour in tests ({@code KafkaIntegrationTest}), so a test pumps the outbox itself.
 */
public final class OutboxPump {

	private OutboxPump() {
	}

	/** @return the events sent */
	public static List<UUID> publishPending(ApplicationContext context) {
		return context.getBean(OutboxRelay.class).publishBatch();
	}

}
