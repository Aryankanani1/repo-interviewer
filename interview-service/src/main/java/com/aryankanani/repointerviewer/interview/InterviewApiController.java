package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.EndResponse;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.ResultError;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.contracts.interview.SubmitAnswerResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

/**
 * The interview side's internal API (task 4.1): {@link InterviewApi} over {@link InterviewService}. In one JVM the
 * GraphQL resolvers get this bean itself as their {@code InterviewApi}, so they already use only the contract; from
 * task 4.1 step 3b they reach it over HTTP. Errors: {@link InterviewApiErrors}.
 */
@RestController
class InterviewApiController implements InterviewApi {

	private final InterviewService interviewService;

	InterviewApiController(InterviewService interviewService) {
		this.interviewService = interviewService;
	}

	@Override
	public StartResponse start(StartInterviewRequest request) {
		requireUser(request.userId());
		StartResult result = interviewService.start(new StartInterviewCommand(request.userId(), request.projectId(),
				request.difficulty(), request.focusAreas(), request.questionCount()));
		return new StartResponse(toDto(result.session()),
				result.firstQuestion() == null ? null : toDto(result.firstQuestion()), toError(result.error()));
	}

	@Override
	public SubmitAnswerResponse submitAnswer(UUID id, AnswerRequest request) {
		SubmitAnswerResult result = interviewService.submitAnswer(new SubmitAnswerCommand(id, request.text()));
		return new SubmitAnswerResponse(toDto(result.answer(), result.question()),
				result.nextQuestion() == null ? null : toDto(result.nextQuestion()), toDto(result.session()),
				toError(result.error()));
	}

	@Override
	public EndResponse end(UUID id) {
		EndResult result = interviewService.endInterview(new EndInterviewCommand(id));
		return new EndResponse(toDto(result.session()), toError(result.error()));
	}

	@Override
	public List<SessionDto> sessions(List<UUID> ids) {
		return interviewService.sessionsByIds(ids).stream().map(InterviewApiController::toDto).toList();
	}

	@Override
	public List<SessionDto> sessionsByProject(List<UUID> projectIds) {
		return interviewService.sessionsByProjectIds(projectIds).values().stream().flatMap(Collection::stream)
				.map(InterviewApiController::toDto).toList();
	}

	@Override
	public List<QuestionDto> questions(List<UUID> ids) {
		return interviewService.questionsByIds(ids).values().stream()
				.filter(question -> question.getOrderIndex() != null)   // prepared ahead, not asked yet
				.map(InterviewApiController::toDto).toList();
	}

	@Override
	public List<QuestionDto> askedQuestions(List<UUID> sessionIds) {
		return interviewService.askedQuestionsBySessionIds(sessionIds).values().stream().flatMap(Collection::stream)
				.map(InterviewApiController::toDto).toList();
	}

	/** Two queries for all answers: the answers, then their questions' ideal outlines. */
	@Override
	public List<AnswerDto> answers(List<UUID> questionIds) {
		Map<UUID, Answer> answers = interviewService.answersByQuestionIds(questionIds);
		if (answers.isEmpty()) {
			return List.of();
		}
		Map<UUID, Question> questions = interviewService.questionsByIds(answers.keySet());
		return answers.values().stream().map(answer -> toDto(answer, questions.get(answer.getQuestionId()))).toList();
	}

	@Override
	public void deleteByProject(UUID projectId) {
		interviewService.deleteSessionsOfProject(projectId);
	}

	/** The gateway always passes the caller (task 4.4); a request without one is a bug, refused before any work. */
	private static void requireUser(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new DomainException(ErrorCode.INVALID_INPUT, "userId is required.", "userId");
		}
	}

	private static SessionDto toDto(InterviewSession session) {
		return new SessionDto(session.getId(), session.getUserId(), session.getProjectId(), session.getStatus(),
				session.getDifficulty(), session.getFocusAreas(), session.getPlannedQuestionCount(),
				session.getFailureReason(), session.getReport(), session.getStartedAt(), session.getCompletedAt(),
				session.getCurrentQuestionId());
	}

	private static QuestionDto toDto(Question question) {
		return new QuestionDto(question.getId(), question.getSessionId(), question.getOrderIndex(),
				question.getCategory(), question.getText(), question.isFollowUp(), question.getCodeRefs(),
				question.getAskedAt(), question.getParentQuestionId());
	}

	/** The outline comes from the answered question, and only with the grade. */
	private static AnswerDto toDto(Answer answer, Question question) {
		AnswerDto.Evaluation evaluation = answer.getEvaluatedAt() == null ? null
				: new AnswerDto.Evaluation(answer.getScore(), answer.getVerdict(), answer.getFeedback(),
						answer.getCoveredPoints(), answer.getMissedPoints(), answer.getIncorrectClaims(),
						question.getIdealAnswerOutline(), answer.getEvaluatedAt());
		return new AnswerDto(answer.getId(), answer.getQuestionId(), answer.getText(), answer.getSubmittedAt(),
				evaluation);
	}

	private static ResultError toError(DomainException error) {
		return error == null ? null
				: new ResultError(error.code().name(), error.getMessage(), error.field().orElse(null));
	}

}
