package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.InputValidator;
import com.aryankanani.repointerviewer.api.error.UserErrorMapper;
import com.aryankanani.repointerviewer.codebase.CodebaseQueries;
import com.aryankanani.repointerviewer.codebase.store.SnippetRef;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.interview.Answer;
import com.aryankanani.repointerviewer.interview.EndInterviewCommand;
import com.aryankanani.repointerviewer.interview.EndResult;
import com.aryankanani.repointerviewer.interview.InterviewService;
import com.aryankanani.repointerviewer.interview.InterviewSession;
import com.aryankanani.repointerviewer.interview.Question;
import com.aryankanani.repointerviewer.interview.StartInterviewCommand;
import com.aryankanani.repointerviewer.interview.StartResult;
import com.aryankanani.repointerviewer.interview.SubmitAnswerCommand;
import com.aryankanani.repointerviewer.interview.SubmitAnswerResult;
import com.aryankanani.repointerviewer.project.Project;
import com.aryankanani.repointerviewer.project.ProjectService;
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
 */
@Controller
class InterviewController {

	private final InterviewService interviewService;

	private final ProjectService projectService;

	private final CodebaseQueries codebaseQueries;

	private final InputValidator inputValidator;

	InterviewController(InterviewService interviewService, ProjectService projectService,
			CodebaseQueries codebaseQueries, InputValidator inputValidator) {
		this.interviewService = interviewService;
		this.projectService = projectService;
		this.codebaseQueries = codebaseQueries;
		this.inputValidator = inputValidator;
	}

	/** {@code startInterview(input): StartInterviewPayload!}. Can take several seconds: two or more model calls. */
	@MutationMapping
	StartInterviewPayload startInterview(@Argument StartInterviewInput input) {
		var command = new StartInterviewCommand(input.projectId(), input.difficulty(), input.focusAreas(),
				input.questionCount());
		try {
			StartResult result = interviewService.start(command);
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
	SubmitAnswerPayload submitAnswer(@Argument SubmitAnswerInput input) {
		var invalid = inputValidator.firstError(input);
		if (invalid.isPresent()) {
			return new SubmitAnswerPayload(null, null, null, List.of(invalid.get()));
		}
		try {
			SubmitAnswerResult result = interviewService.submitAnswer(
					new SubmitAnswerCommand(input.questionId(), input.text()));
			return new SubmitAnswerPayload(AnswerView.from(result.answer(), result.question()),
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
	EndInterviewPayload endInterview(@Argument EndInterviewInput input) {
		try {
			EndResult result = interviewService.endInterview(new EndInterviewCommand(input.sessionId()));
			return new EndInterviewPayload(InterviewSessionView.from(result.session()),
					result.error() == null ? List.of() : List.of(UserErrorMapper.toUserError(result.error())));
		}
		catch (DomainException ex) {
			return new EndInterviewPayload(null, List.of(UserErrorMapper.toUserError(ex)));
		}
	}

	/** {@code interviewSession(id: ID!): InterviewSession}. Not found returns null. */
	@QueryMapping
	InterviewSessionView interviewSession(@Argument UUID id) {
		return interviewService.find(id).map(InterviewSessionView::from).orElse(null);
	}

	// Field resolvers, batched (task 1.13). Spring collects every parent object at one level of the query and calls
	// each method once with all of them (the DataLoader pattern), so a field costs one query however many parents
	// there are. Two return shapes:
	//  - a List, one value per parent in the same order, for fields that always have a value;
	//  - a Map from parent to value for fields that can be null: Spring streams the results through Reactor, which
	//    does not allow null elements, so a parent without a value is simply left out of the map.

	@BatchMapping(typeName = "Project")
	List<List<InterviewSessionView>> sessions(List<ProjectView> projects) {
		Map<UUID, List<InterviewSession>> byProject = interviewService.sessionsByProjectIds(
				projects.stream().map(ProjectView::id).toList());
		return projects.stream()
				.map(project -> byProject.getOrDefault(project.id(), List.of()).stream()
						.map(InterviewSessionView::from).toList())
				.toList();
	}

	@BatchMapping(typeName = "InterviewSession")
	List<ProjectView> project(List<InterviewSessionView> sessions) {
		Map<UUID, Project> projects = projectService.findByIds(
				sessions.stream().map(InterviewSessionView::projectId).collect(Collectors.toSet()));
		return sessions.stream().map(session -> ProjectView.from(projects.get(session.projectId()))).toList();
	}

	@BatchMapping(typeName = "InterviewSession")
	List<List<QuestionView>> questions(List<InterviewSessionView> sessions) {
		Map<UUID, List<Question>> bySession = interviewService.askedQuestionsBySessionIds(
				sessions.stream().map(InterviewSessionView::id).toList());
		return sessions.stream()
				.map(session -> bySession.getOrDefault(session.id(), List.of()).stream().map(QuestionView::from).toList())
				.toList();
	}

	@BatchMapping(typeName = "InterviewSession")
	Map<InterviewSessionView, QuestionView> currentQuestion(List<InterviewSessionView> sessions) {
		return linked(sessions, InterviewSessionView::currentQuestionId, ids -> interviewService.questionsByIds(ids),
				QuestionView::from);
	}

	@BatchMapping(typeName = "Question")
	Map<QuestionView, QuestionView> parent(List<QuestionView> questions) {
		return linked(questions, QuestionView::parentQuestionId, ids -> interviewService.questionsByIds(ids),
				QuestionView::from);
	}

	/** Two queries for all questions: their answers, then the answered questions' ideal outlines. */
	@BatchMapping(typeName = "Question")
	Map<QuestionView, AnswerView> answer(List<QuestionView> questions) {
		Map<UUID, Answer> answers = interviewService.answersByQuestionIds(
				questions.stream().map(QuestionView::id).toList());
		if (answers.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Question> answered = interviewService.questionsByIds(answers.keySet());
		Map<QuestionView, AnswerView> result = new LinkedHashMap<>();
		for (QuestionView question : questions) {
			Answer answer = answers.get(question.id());
			if (answer != null) {
				result.put(question, AnswerView.from(answer, answered.get(question.id())));
			}
		}
		return result;
	}

	/** Two queries for every ref of the response: which project each session belongs to, then all the snippets. */
	@BatchMapping(typeName = "CodeRef")
	Map<CodeRefView, String> snippet(List<CodeRefView> refs) {
		Map<UUID, UUID> projects = interviewService.projectIdsBySessionIds(
				refs.stream().map(CodeRefView::sessionId).collect(Collectors.toSet()));
		List<String> snippets = codebaseQueries.snippets(refs.stream()
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

	/**
	 * For a nullable link to another row by id: loads every linked row in one query (none when no parent has a
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
