package com.aryankanani.repointerviewer.outbox;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * No trace per outbox poll (task 4.3). Boot observes every {@code @Scheduled} run, and the relay polls every 500 ms in
 * each service: four empty traces a second would bury the real ones. Each event's send is still traced
 * ({@link OutboxTracing}). {@link OutboxRelay} is the only {@code @Scheduled} method in the system, so this drops
 * nothing else (its {@code tasks.scheduled.execution} timer goes with it).
 */
@Configuration(proxyBeanMethods = false)
class OutboxObservations {

	@Bean
	ObservationPredicate noObservationPerOutboxPoll() {
		return (name, context) -> !(context instanceof ScheduledTaskObservationContext);
	}

}
