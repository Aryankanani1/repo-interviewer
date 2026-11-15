package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.events.InterviewEvent;
import com.aryankanani.repointerviewer.api.events.InterviewEventStream;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Resolvers for {@code Subscription} (task 2.5). A subscription is a request that stays open: the resolver returns a
 * {@link Flux}, and Spring for GraphQL runs the selection set once per element and sends each result to the client.
 * Clients subscribe over Server-Sent Events: a POST to {@code /graphql} with {@code Accept: text/event-stream}
 * (DECISIONS.md D-020).
 *
 * <p>Only with the {@code kafka} profile do events arrive; without it {@code submitAnswer} returns everything itself.
 * {@code projectAnalysisProgress} (task 1.18) has no resolver yet.
 */
@Controller
class SubscriptionController {

	private final InterviewEventStream stream;

	private final InterviewApi interviews;

	SubscriptionController(InterviewEventStream stream, InterviewApi interviews) {
		this.stream = stream;
		this.interviews = interviews;
	}

	/**
	 * {@code interviewEvents(sessionId: ID!): InterviewEvent!}: this session's events from now on, none from before.
	 * The reads (blocking calls) run on Reactor's thread pool for blocking work, not on the Kafka listener thread
	 * that emits.
	 */
	@SubscriptionMapping
	Flux<InterviewEventView> interviewEvents(@Argument UUID sessionId) {
		return stream.forSession(sessionId).publishOn(Schedulers.boundedElastic()).map(this::view);
	}

	/** Three calls at most: the answer, the question just asked, the session. */
	private InterviewEventView view(InterviewEvent event) {
		AnswerView answer = null;
		if (event.questionId() != null) {
			answer = interviews.answers(List.of(event.questionId())).stream().findFirst().map(AnswerView::from)
					.orElse(null);
		}
		QuestionDto next = event.nextQuestionId() == null ? null
				: interviews.questions(List.of(event.nextQuestionId())).stream().findFirst().orElse(null);
		return new InterviewEventView(event.sessionId(), event.type(), answer,
				next == null ? null : QuestionView.from(next),
				interviews.sessions(List.of(event.sessionId())).stream().findFirst().map(InterviewSessionView::from)
						.orElse(null),
				event.at().atOffset(ZoneOffset.UTC));
	}

}
