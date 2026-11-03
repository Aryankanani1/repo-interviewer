package com.aryankanani.repointerviewer.interview;

import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The {@code interviewEvents} subscriptions of this app instance (task 2.5): {@code InterviewEventsFanOut} puts each
 * event from Kafka in, every open subscription takes its session's events out.
 *
 * <p>A Reactor {@link Sinks.Many} in multicast, best-effort mode: an event goes to everyone subscribed at that moment
 * and is not kept for later subscribers, and a subscriber too slow to take it misses it. That is fine for a live view:
 * everything is in the database, and a client that missed something reads {@code interviewSession}.
 *
 * <p>Without the {@code kafka} profile nothing publishes here: {@code submitAnswer} itself returns the grade and the
 * next question.
 */
@Component
public class InterviewEventStream {

	private final Sinks.Many<InterviewEvent> sink = Sinks.many().multicast().directBestEffort();

	public Flux<InterviewEvent> forSession(UUID sessionId) {
		return sink.asFlux().filter(event -> event.sessionId().equals(sessionId));
	}

	/** Synchronized: a Reactor sink must not be called from two threads at once. No subscriber is not an error. */
	synchronized void publish(InterviewEvent event) {
		sink.tryEmitNext(event);
	}

}
