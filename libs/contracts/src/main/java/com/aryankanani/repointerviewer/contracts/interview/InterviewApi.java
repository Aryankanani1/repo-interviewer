package com.aryankanani.repointerviewer.contracts.interview;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * What the GraphQL API may ask the interview side (task 4.1, LLD §2): run an interview, and read sessions, questions
 * and answers. Like {@code AnalysisApi}, one interface for both ends: the interview side's {@code @RestController}
 * implements it and a caller gets an HTTP client for it from Spring.
 *
 * <p>Expected failures before anything is saved (unknown id, empty answer, project not ready, budget) are a
 * {@code DomainException} on the server and a problem detail on the wire, with the {@code ErrorCode} in {@code code}
 * and the input field in {@code field}. A failure after something was saved comes back inside the response as a
 * {@link ResultError}, next to what was saved.
 *
 * <p>The batch reads serve GraphQL's batch resolvers: one call for all parents of a field. They return what exists
 * among the ids, in no particular order unless stated, and leave unknown ids out.
 */
@HttpExchange("/internal")
public interface InterviewApi {

	/** Plans the interview and asks the first question: two or more model calls. */
	@PostExchange("/sessions")
	StartResponse start(@RequestBody StartInterviewRequest request);

	/** Saves the answer, grades it and asks the next question (later, on Kafka). Safe to send again. */
	@PostExchange("/questions/{id}/answer")
	SubmitAnswerResponse submitAnswer(@PathVariable UUID id, @RequestBody AnswerRequest request);

	/** Ends the session early and writes the report. Safe to call again: it only retries a missing report. */
	@PostExchange("/sessions/{id}/end")
	EndResponse end(@PathVariable UUID id);

	@GetExchange("/sessions")
	List<SessionDto> sessions(@RequestParam List<UUID> ids);

	/** The sessions of these projects, each project's newest first. */
	@GetExchange("/sessions/by-project")
	List<SessionDto> sessionsByProject(@RequestParam List<UUID> projectIds);

	/** Only questions that were asked. */
	@GetExchange("/questions")
	List<QuestionDto> questions(@RequestParam List<UUID> ids);

	/** The questions asked in these sessions, each session's in order. */
	@GetExchange("/questions/asked")
	List<QuestionDto> askedQuestions(@RequestParam List<UUID> sessionIds);

	/** The answers to these questions, for the questions that have one. */
	@GetExchange("/answers")
	List<AnswerDto> answers(@RequestParam List<UUID> questionIds);

}
