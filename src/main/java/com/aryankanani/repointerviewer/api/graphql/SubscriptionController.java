package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.interview.Answer;
import com.aryankanani.repointerviewer.interview.InterviewEvent;
import com.aryankanani.repointerviewer.interview.InterviewEventStream;
import com.aryankanani.repointerviewer.interview.InterviewService;
import com.aryankanani.repointerviewer.interview.Question;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

	private final InterviewService interviewService;

	SubscriptionController(InterviewEventStream stream, InterviewService interviewService) {
		this.stream = stream;
		this.interviewService = interviewService;
	}

	/**
	 * {@code interviewEvents(sessionId: ID!): InterviewEvent!}: this session's events from now on, none from before.
	 * The database reads run on Reactor's thread pool for blocking work, not on the Kafka listener thread that emits.
	 */
	@SubscriptionMapping
	Flux<InterviewEventView> interviewEvents(@Argument UUID sessionId) {
		return stream.forSession(sessionId).publishOn(Schedulers.boundedElastic()).map(this::view);
	}

	private InterviewEventView view(InterviewEvent event) {
		Set<UUID> ids = Stream.of(event.questionId(), event.nextQuestionId()).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<UUID, Question> questions = ids.isEmpty() ? Map.of() : interviewService.questionsByIds(ids);
		AnswerView answer = null;
		if (event.questionId() != null) {
			Answer stored = interviewService.answersByQuestionIds(List.of(event.questionId())).get(event.questionId());
			answer = stored == null ? null : AnswerView.from(stored, questions.get(event.questionId()));
		}
		Question next = event.nextQuestionId() == null ? null : questions.get(event.nextQuestionId());
		return new InterviewEventView(event.sessionId(), event.type(), answer,
				next == null ? null : QuestionView.from(next),
				interviewService.find(event.sessionId()).map(InterviewSessionView::from).orElse(null),
				event.at().atOffset(ZoneOffset.UTC));
	}

}
