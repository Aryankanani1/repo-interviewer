package com.aryankanani.repointerviewer.api.security;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Who may reach what (task 4.4, DECISIONS.md D-030): a project is its submitter's and a session its starter's, as
 * stored by the services ({@code userId}). The gateway is the one place that compares them with the caller: the
 * services trust it and only store and filter. Each check is one batch read the operation would mostly make anyway.
 *
 * <p>Unknown is NOT_FOUND and someone else's is FORBIDDEN, both on the input field that named it, with the same
 * messages the services use for NOT_FOUND.
 */
@Component
public class Ownership {

	private final AnalysisApi analysis;

	private final InterviewApi interviews;

	Ownership(AnalysisApi analysis, InterviewApi interviews) {
		this.analysis = analysis;
		this.interviews = interviews;
	}

	/** @throws DomainException NOT_FOUND or FORBIDDEN, on {@code projectId} */
	public ProjectDto project(UUID projectId, String userId) {
		return owned(analysis.findByIds(List.of(projectId)).stream().findFirst(), ProjectDto::userId, userId,
				"Project not found.", "This project belongs to another user.", "projectId");
	}

	/** @throws DomainException NOT_FOUND or FORBIDDEN, on {@code sessionId} */
	public SessionDto session(UUID sessionId, String userId) {
		return owned(interviews.sessions(List.of(sessionId)).stream().findFirst(), SessionDto::userId, userId,
				"Interview not found.", "This interview belongs to another user.", "sessionId");
	}

	/** The session a question belongs to. @throws DomainException NOT_FOUND or FORBIDDEN, on {@code questionId} */
	public SessionDto sessionOfQuestion(UUID questionId, String userId) {
		Optional<SessionDto> session = interviews.questions(List.of(questionId)).stream().findFirst()
				.map(QuestionDto::sessionId)
				.flatMap(sessionId -> interviews.sessions(List.of(sessionId)).stream().findFirst());
		return owned(session, SessionDto::userId, userId, "Question not found.",
				"This interview belongs to another user.", "questionId");
	}

	/**
	 * For a query, where unknown is null rather than an error: empty stays empty, someone else's is FORBIDDEN.
	 *
	 * @throws DomainException FORBIDDEN, with no field (a query has no input)
	 */
	public static <T> Optional<T> visible(Optional<T> found, Function<T, String> owner,
			String userId, String forbidden) {
		found.filter(value -> !owner.apply(value).equals(userId)).ifPresent(value -> {
			throw new DomainException(ErrorCode.FORBIDDEN, forbidden);
		});
		return found;
	}

	private static <T> T owned(Optional<T> found, Function<T, String> owner, String userId,
			String notFound, String forbidden, String field) {
		T value = found.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, notFound, field));
		if (!owner.apply(value).equals(userId)) {
			throw new DomainException(ErrorCode.FORBIDDEN, forbidden, field);
		}
		return value;
	}

}
