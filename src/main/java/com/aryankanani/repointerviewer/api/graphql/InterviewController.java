package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserErrorMapper;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.interview.InterviewService;
import com.aryankanani.repointerviewer.interview.StartInterviewCommand;
import com.aryankanani.repointerviewer.interview.StartResult;
import com.aryankanani.repointerviewer.project.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * Resolvers for {@code Mutation.startInterview}, {@code Query.interviewSession} and the fields that link sessions,
 * projects and questions.
 *
 * <p>The {@code @SchemaMapping} resolvers below run once per parent object: a list of 20 sessions asking for their
 * project runs 20 queries (the N+1 problem). That is on purpose for now: task 1.13 measures it and replaces them
 * with {@code @BatchMapping}, which loads all of them in one query.
 */
@Controller
class InterviewController {

	private final InterviewService interviewService;

	private final ProjectService projectService;

	InterviewController(InterviewService interviewService, ProjectService projectService) {
		this.interviewService = interviewService;
		this.projectService = projectService;
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

	/** {@code interviewSession(id: ID!): InterviewSession}. Not found returns null. */
	@QueryMapping
	InterviewSessionView interviewSession(@Argument UUID id) {
		return interviewService.find(id).map(InterviewSessionView::from).orElse(null);
	}

	@SchemaMapping(typeName = "InterviewSession")
	ProjectView project(InterviewSessionView session) {
		return projectService.find(session.projectId()).map(ProjectView::from).orElseThrow();
	}

	@SchemaMapping(typeName = "InterviewSession")
	List<QuestionView> questions(InterviewSessionView session) {
		return interviewService.askedQuestions(session.id()).stream().map(QuestionView::from).toList();
	}

	@SchemaMapping(typeName = "InterviewSession")
	QuestionView currentQuestion(InterviewSessionView session) {
		if (session.currentQuestionId() == null) {
			return null;
		}
		return interviewService.question(session.currentQuestionId()).map(QuestionView::from).orElse(null);
	}

	@SchemaMapping(typeName = "Project")
	List<InterviewSessionView> sessions(ProjectView project) {
		return interviewService.sessionsOf(project.id()).stream().map(InterviewSessionView::from).toList();
	}

}
