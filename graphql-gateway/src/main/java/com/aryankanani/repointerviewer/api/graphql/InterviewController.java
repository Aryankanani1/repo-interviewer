package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.InputValidator;
import com.aryankanani.repointerviewer.api.error.UserErrorMapper;
import com.aryankanani.repointerviewer.api.security.CurrentUser;
import com.aryankanani.repointerviewer.api.security.Ownership;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.SnippetRef;
import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.EndResponse;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.contracts.interview.SubmitAnswerResponse;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Resolvers for {@code Mutation.startInterview}, {@code .submitAnswer}, {@code .endInterview},
 * {@code Query.interviewSession} and
 * the fields that link sessions, projects, questions and answers.
 *
 * <p>Nested fields are {@code @BatchMapping} resolvers (task 1.13). A plain {@code @SchemaMapping} runs once per
 * parent object, so a session with 8 questions asking for their answers ran 8 queries: the N+1 problem. A batch
 * resolver runs once per field for all parents. {@code NPlusOneTest} proves the count stays fixed.
 *
 * <p>Everything about interviews comes from {@link InterviewApi} as records (task 4.1), never from the interview
 * side's entities. The operations reach only the caller's projects and sessions (task 4.4, {@link Ownership}); the
 * nested fields below follow from a parent the caller was allowed to see.
 */
@Controller
class InterviewController {

	private final InterviewApi interviews;

	private final AnalysisApi analysis;

	private final InputValidator inputValidator;

	private final Ownership ownership;

	InterviewController(InterviewApi interviews, AnalysisApi analysis, InputValidator inputValidator,
			Ownership ownership) {
		this.interviews = interviews;
		this.analysis = analysis;
		this.inputValidator = inputValidator;
		this.ownership = ownership;
	}

	/** {@code startInterview(input): StartInterviewPayload!}. Can take several seconds: two or more model calls. */
	@MutationMapping
	StartInterviewPayload startInterview(@Argument StartInterviewInput input, Principal user) {
		String userId = CurrentUser.id(user);
		var request = new StartInterviewRequest(userId, input.projectId(), input.difficulty(), input.focusAreas(),
				input.questionCount());
		try {
			ownership.project(input.projectId(), userId);
			StartResponse result = interviews.start(request);
			return new StartInterviewPayload(InterviewSessionView.from(result.session()),
					result.firstQuestion() == null ? null : QuestionView.from(result.firstQuestion()),
					result.error() == null ? List.of() : List.of(UserErrorMapper.toUserError(result.error())));
		}
		catch (DomainException ex) {
			return new StartInterviewPayload(null, null, List.of(UserErrorMapper.toUserError(ex)));
		}
	}

	/**
	 * {@code submitAnswer(input): SubmitAnswerPayload!}. Grades the answer and returns the next question, or the
	 * completed session. Safe to send again with the same text.
	 */
	@MutationMapping
	SubmitAnswerPayload submitAnswer(@Argument SubmitAnswerInput input, Principal user) {
		var invalid = inputValidator.firstError(input);
		if (invalid.isPresent()) {
			return new SubmitAnswerPayload(null, null, null, List.of(invalid.get()));
		}
		try {
			ownership.sessionOfQuestion(input.questionId(), CurrentUser.id(user));
			SubmitAnswerResponse result = interviews.submitAnswer(input.questionId(), new AnswerRequest(input.text()));
			return new SubmitAnswerPayload(AnswerView.from(result.answer()),
					result.nextQuestion() == null ? null : QuestionView.from(result.nextQuestion()),
					InterviewSessionView.from(result.session()),
					result.error() == null ? List.of() : List.of(UserErrorMapper.toUserError(result.error())));
		}
		catch (DomainException ex) {
			return new SubmitAnswerPayload(null, null, null, List.of(UserErrorMapper.toUserError(ex)));
		}
	}

	/**
	 * {@code endInterview(input): EndInterviewPayload!}. Stops early and writes the report from the graded answers.
	 * Safe to call again: on an ended session it only retries a missing report.
	 */
	@MutationMapping
	EndInterviewPayload endInterview(@Argument EndInterviewInput input, Principal user) {
		try {
			ownership.session(input.sessionId(), CurrentUser.id(user));
			EndResponse result = interviews.end(input.sessionId());
			return new EndInterviewPayload(InterviewSessionView.from(result.session()),
					result.error() == null ? List.of() : List.of(UserErrorMapper.toUserError(result.error())));
		}
		catch (DomainException ex) {
			return new EndInterviewPayload(null, List.of(UserErrorMapper.toUserError(ex)));
		}
	}

	/**
	 * {@code interviewSession(id: ID!): InterviewSession}. Not found returns null; another user's session is a
	 * FORBIDDEN error.
	 */
	@QueryMapping
	InterviewSessionView interviewSession(@Argument UUID id, Principal user) {
		return Ownership.visible(interviews.sessions(List.of(id)).stream().findFirst(), SessionDto::userId,
				CurrentUser.id(user), "This interview belongs to another user.").map(InterviewSessionView::from)
				.orElse(null);
	}

	// Field resolvers, batched (task 1.13). Spring collects every parent object at one level of the query and calls
	// each method once with all of them (the DataLoader pattern), so a field costs one call however many parents
	// there are. Two return shapes:
	//  - a List, one value per parent in the same order, for fields that always have a value;
	//  - a Map from parent to value for fields that can be null: Spring streams the results through Reactor, which
	//    does not allow null elements, so a parent without a value is simply left out of the map.

	@BatchMapping(typeName = "Project")
	List<List<InterviewSessionView>> sessions(List<ProjectView> projects) {
		Map<UUID, List<SessionDto>> byProject = interviews.sessionsByProject(
				projects.stream().map(ProjectView::id).toList())
				.stream()
				.collect(Collectors.groupingBy(SessionDto::projectId, LinkedHashMap::new, Collectors.toList()));
		return projects.stream()
				.map(project -> byProject.getOrDefault(project.id(), List.of()).stream()
						.map(InterviewSessionView::from).toList())
				.toList();
	}

	@BatchMapping(typeName = "InterviewSession")
	List<ProjectView> project(List<InterviewSessionView> sessions) {
		Map<UUID, ProjectDto> projects = analysis.findByIds(
				sessions.stream().map(InterviewSessionView::projectId).distinct().toList())
				.stream().collect(Collectors.toMap(ProjectDto::id, Function.identity()));
		return sessions.stream().map(session -> ProjectView.from(projects.get(session.projectId()))).toList();
	}

	@BatchMapping(typeName = "InterviewSession")
	List<List<QuestionView>> questions(List<InterviewSessionView> sessions) {
		Map<UUID, List<QuestionDto>> bySession = interviews.askedQuestions(
				sessions.stream().map(InterviewSessionView::id).toList())
				.stream()
				.collect(Collectors.groupingBy(QuestionDto::sessionId, LinkedHashMap::new, Collectors.toList()));
		return sessions.stream()
				.map(session -> bySession.getOrDefault(session.id(), List.of()).stream().map(QuestionView::from).toList())
				.toList();
	}

	@BatchMapping(typeName = "InterviewSession")
	Map<InterviewSessionView, QuestionView> currentQuestion(List<InterviewSessionView> sessions) {
		return linked(sessions, InterviewSessionView::currentQuestionId, this::questionsById, QuestionView::from);
	}

	@BatchMapping(typeName = "Question")
	Map<QuestionView, QuestionView> parent(List<QuestionView> questions) {
		return linked(questions, QuestionView::parentQuestionId, this::questionsById, QuestionView::from);
	}

	/** One call for all questions: their answers, each graded one with its question's ideal outline. */
	@BatchMapping(typeName = "Question")
	Map<QuestionView, AnswerView> answer(List<QuestionView> questions) {
		Map<UUID, AnswerDto> answers = interviews.answers(questions.stream().map(QuestionView::id).toList())
				.stream().collect(Collectors.toMap(AnswerDto::questionId, Function.identity()));
		Map<QuestionView, AnswerView> result = new LinkedHashMap<>();
		for (QuestionView question : questions) {
			AnswerDto answer = answers.get(question.id());
			if (answer != null) {
				result.put(question, AnswerView.from(answer));
			}
		}
		return result;
	}

	/** For every ref of the response: one call for which project each session belongs to, one for the snippets. */
	@BatchMapping(typeName = "CodeRef")
	Map<CodeRefView, String> snippet(List<CodeRefView> refs) {
		Map<UUID, UUID> projects = interviews.sessions(refs.stream().map(CodeRefView::sessionId).distinct().toList())
				.stream().collect(Collectors.toMap(SessionDto::id, SessionDto::projectId));
		List<String> snippets = analysis.snippets(refs.stream()
				.map(ref -> new SnippetRef(projects.get(ref.sessionId()), ref.path(), ref.startLine(), ref.endLine()))
				.toList());
		Map<CodeRefView, String> result = new LinkedHashMap<>();
		for (int i = 0; i < refs.size(); i++) {
			if (snippets.get(i) != null) {
				result.put(refs.get(i), snippets.get(i));
			}
		}
		return result;
	}

	private Map<UUID, QuestionDto> questionsById(Set<UUID> ids) {
		return interviews.questions(List.copyOf(ids)).stream()
				.collect(Collectors.toMap(QuestionDto::id, Function.identity()));
	}

	/**
	 * For a nullable link to another row by id: loads every linked row in one call (none when no parent has a
	 * link) and maps each parent to its view. Parents without a link are left out of the map, which means null.
	 */
	private static <P, E, V> Map<P, V> linked(List<P> parents, Function<P, UUID> link,
			Function<Set<UUID>, Map<UUID, E>> load, Function<E, V> view) {
		Set<UUID> ids = parents.stream().map(link).filter(Objects::nonNull).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, E> rows = load.apply(ids);
		Map<P, V> result = new LinkedHashMap<>();
		for (P parent : parents) {
			E row = link.apply(parent) == null ? null : rows.get(link.apply(parent));
			if (row != null) {
				result.put(parent, view.apply(row));
			}
		}
		return result;
	}

}
