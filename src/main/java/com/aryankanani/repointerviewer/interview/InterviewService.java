package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.interview.evaluation.AnswerEvaluator;
import com.aryankanani.repointerviewer.interview.evaluation.Evaluation;
import com.aryankanani.repointerviewer.interview.evaluation.FollowUpPolicy;
import com.aryankanani.repointerviewer.interview.planning.QuestionPlanner;
import com.aryankanani.repointerviewer.interview.question.GeneratedQuestion;
import com.aryankanani.repointerviewer.interview.question.QuestionGenerator;
import com.aryankanani.repointerviewer.interview.report.ReportWriter;
import com.aryankanani.repointerviewer.interview.report.TranscriptEntry;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import com.aryankanani.repointerviewer.project.AnalysisStatus;
import com.aryankanani.repointerviewer.project.ProjectContext;
import com.aryankanani.repointerviewer.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Interview use cases (LLD §9): {@link #start} (task 1.10), {@link #submitAnswer} (task 1.11) and
 * {@link #endInterview} with the report (task 1.12).
 *
 * <p>Never holds a database transaction across a model call (CLAUDE.md): short transaction → model call with no
 * transaction → short transaction with a version check. The use cases themselves are therefore not
 * {@code @Transactional}.
 */
@Service
public class InterviewService {

	static final String NO_GROUNDED_QUESTION = "Could not write a question grounded in this repository's code. "
			+ "Try again.";

	private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

	private final ProjectService projectService;

	private final InterviewSessionRepository sessions;

	private final InterviewTopicRepository topics;

	private final QuestionRepository questions;

	private final AnswerRepository answers;

	private final QuestionPlanner planner;

	private final QuestionGenerator generator;

	private final AnswerEvaluator evaluator;

	private final FollowUpPolicy followUpPolicy;

	private final ReportWriter reportWriter;

	private final TransactionTemplate transaction;

	private final AppProperties.Interview settings;

	InterviewService(ProjectService projectService, InterviewSessionRepository sessions,
			InterviewTopicRepository topics, QuestionRepository questions, AnswerRepository answers,
			QuestionPlanner planner, QuestionGenerator generator, AnswerEvaluator evaluator,
			FollowUpPolicy followUpPolicy, ReportWriter reportWriter, PlatformTransactionManager transactionManager,
			AppProperties properties) {
		this.projectService = projectService;
		this.sessions = sessions;
		this.topics = topics;
		this.questions = questions;
		this.answers = answers;
		this.planner = planner;
		this.generator = generator;
		this.evaluator = evaluator;
		this.followUpPolicy = followUpPolicy;
		this.reportWriter = reportWriter;
		this.transaction = new TransactionTemplate(transactionManager);
		this.settings = properties.interview();
	}

	/**
	 * Starts an interview (LLD §9.1): checks, session PLANNING, plan (PLAN call + {@code TopicValidator}), topics,
	 * then the first topic's question ({@code QuestionGenerator}); a topic without a grounded question is SKIPPED
	 * and the next one is tried.
	 *
	 * @throws DomainException NOT_FOUND, PROJECT_NOT_READY or INVALID_QUESTION_COUNT, before anything is saved
	 */
	public StartResult start(StartInterviewCommand command) {
		ProjectContext project = projectService.context(command.projectId())
				.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Project not found.", "projectId"));
		if (project.status() != AnalysisStatus.READY) {
			throw new DomainException(ErrorCode.PROJECT_NOT_READY,
					"The project is not analyzed yet (status " + project.status() + ").", "projectId");
		}
		int count = command.questionCount() == null ? settings.defaultQuestionCount() : command.questionCount();
		if (count < settings.minQuestionCount() || count > settings.maxQuestionCount()) {
			throw new DomainException(ErrorCode.INVALID_QUESTION_COUNT, "Choose between "
					+ settings.minQuestionCount() + " and " + settings.maxQuestionCount() + " questions.", "questionCount");
		}
		Difficulty difficulty = command.difficulty() == null ? Difficulty.JUNIOR : command.difficulty();
		List<QuestionCategory> focusAreas = command.focusAreas() == null ? List.of()
				: List.copyOf(new LinkedHashSet<>(command.focusAreas()));

		InterviewSession created = transaction.execute(
				status -> sessions.save(new InterviewSession(project.id(), difficulty, focusAreas, count)));
		UUID sessionId = created.getId();
		MDC.put("sessionId", sessionId.toString());
		try {
			List<PlannedTopic> plan = planner.plan(project, sessionId, difficulty, focusAreas, count);
			List<InterviewTopic> planned = transaction.execute(status -> saveTopics(sessionId, plan));
			for (InterviewTopic topic : planned) {
				Optional<GeneratedQuestion> generated = generator.generate(project.id(), sessionId, difficulty, topic,
						List.of());
				if (generated.isPresent()) {
					Question first = transaction.execute(status -> askFirst(sessionId, topic.getId(), generated.get()));
					log.info("Interview started: {} topics, first question on topic {}", planned.size(),
							topic.getOrderIndex());
					return new StartResult(sessions.findById(sessionId).orElseThrow(), first, null);
				}
				transaction.executeWithoutResult(status -> topics.findById(topic.getId()).orElseThrow().skip());
			}
			throw new DomainException(ErrorCode.LLM_UNAVAILABLE, NO_GROUNDED_QUESTION);
		}
		catch (DomainException ex) {
			log.warn("Interview could not start: {}", ex.getMessage());
			return new StartResult(fail(sessionId, ex.getMessage()), null, ex);
		}
		catch (RuntimeException ex) {
			fail(sessionId, "The interview could not start. Try again.");   // not left in PLANNING forever
			throw ex;
		}
		finally {
			MDC.remove("sessionId");
		}
	}

	/**
	 * Answers the current question (LLD §9.2): save the answer, grade it (EVALUATION call, verdict in code), then
	 * the next step: a follow-up if {@link FollowUpPolicy} says so, else the next topic's question, else the
	 * session COMPLETED.
	 *
	 * <p>Resumable and idempotent. Each step's result is saved before the next one starts, so sending the same text
	 * again continues where it stopped: a stored grade is not graded again, and a done next step is not redone.
	 * The answer is looked up first, so re-sending the last answer of a finished interview returns its result.
	 *
	 * @throws DomainException ANSWER_EMPTY, ANSWER_TOO_LONG, NOT_FOUND, ALREADY_ANSWERED (another text was saved),
	 *         SESSION_NOT_ACTIVE or NOT_CURRENT_QUESTION, before anything is saved or called
	 */
	public SubmitAnswerResult submitAnswer(SubmitAnswerCommand command) {
		String text = command.text() == null ? "" : command.text().strip();
		if (text.isEmpty()) {
			throw new DomainException(ErrorCode.ANSWER_EMPTY, "Write an answer first. \"I don't know\" is a valid answer.",
					"text");
		}
		if (text.length() > settings.answerMaxChars()) {
			throw new DomainException(ErrorCode.ANSWER_TOO_LONG,
					"An answer can be at most " + settings.answerMaxChars() + " characters.", "text");
		}
		Question question = questions.findById(command.questionId())
				.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Question not found.", "questionId"));
		InterviewSession session = sessions.findById(question.getSessionId()).orElseThrow();
		MDC.put("sessionId", session.getId().toString());
		try {
			Answer answer = existingOrNewAnswer(question, session, text, sha256(text));

			if (!answer.isEvaluated() && session.getStatus() == SessionStatus.IN_PROGRESS) {
				UUID answerId = answer.getId();
				try {
					Evaluation evaluation = evaluator.evaluate(session.getProjectId(), session.getId(),
							session.getDifficulty(), question, answer.getText());
					answer = transaction.execute(status -> saveEvaluation(answerId, question.getId(), evaluation));
				}
				catch (DomainException ex) {
					log.warn("Answer saved but not graded: {}", ex.getMessage());
					return result(question.getId(), answerId, ex);
				}
			}

			InterviewSession current = sessions.findById(session.getId()).orElseThrow();
			if (answer.isEvaluated() && current.getStatus() == SessionStatus.IN_PROGRESS
					&& question.getId().equals(current.getCurrentQuestionId())) {
				try {
					next(current, question, answer);
				}
				catch (DomainException ex) {
					log.warn("Answer graded but the next question failed: {}", ex.getMessage());
					return result(question.getId(), answer.getId(), ex);
				}
				catch (OptimisticLockingFailureException ex) {
					log.info("Another request moved the session on first; returning its result");
				}
			}
			// The session may have just completed, or a re-sent last answer finds its report still missing.
			return result(question.getId(), answer.getId(), writeReportIfMissing(session.getId()));
		}
		finally {
			MDC.remove("sessionId");
		}
	}

	/**
	 * Ends an interview early (LLD §9.6): asked questions without a graded answer become SKIPPED and the session
	 * ABANDONED, then the report is written from the graded answers (none: no report). On an interview that already
	 * ended it only writes a missing report, so calling it again is safe and retries a report that failed.
	 *
	 * @throws DomainException NOT_FOUND, or SESSION_NOT_ACTIVE for a session that is still PLANNING or FAILED
	 */
	public EndResult endInterview(EndInterviewCommand command) {
		InterviewSession session = sessions.findById(command.sessionId())
				.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Interview not found.", "sessionId"));
		if (session.getStatus() == SessionStatus.PLANNING || session.getStatus() == SessionStatus.FAILED) {
			throw new DomainException(ErrorCode.SESSION_NOT_ACTIVE,
					"This interview is " + session.getStatus() + " and cannot be ended.", "sessionId");
		}
		UUID sessionId = session.getId();
		MDC.put("sessionId", sessionId.toString());
		try {
			for (int attempt = 1;; attempt++) {
				try {
					transaction.executeWithoutResult(status -> abandonIfRunning(sessionId));
					break;
				}
				catch (OptimisticLockingFailureException ex) {
					if (attempt == 2) {
						throw ex;
					}
					// A submitAnswer moved the session on between our read and our write: look again.
				}
			}
			DomainException reportError = writeReportIfMissing(sessionId);   // first: the session read below must see it
			return new EndResult(sessions.findById(sessionId).orElseThrow(), reportError);
		}
		finally {
			MDC.remove("sessionId");
		}
	}

	@Transactional(readOnly = true)
	public Optional<InterviewSession> find(UUID sessionId) {
		return sessions.findById(sessionId);
	}

	/** The questions shown so far, in order. Prepared (prefetched) ones are never returned. */
	@Transactional(readOnly = true)
	public List<Question> askedQuestions(UUID sessionId) {
		return questions.findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(sessionId);
	}

	// Batch reads for GraphQL (@BatchMapping, task 1.13): one query for many parents, keyed by the parent's id.

	/** Project id to its sessions, newest first. One query. */
	@Transactional(readOnly = true)
	public Map<UUID, List<InterviewSession>> sessionsByProjectIds(Collection<UUID> projectIds) {
		return sessions.findByProjectIdInOrderByStartedAtDesc(projectIds).stream()
				.collect(Collectors.groupingBy(InterviewSession::getProjectId, LinkedHashMap::new, Collectors.toList()));
	}

	/** Session id to its asked questions, in order. One query. */
	@Transactional(readOnly = true)
	public Map<UUID, List<Question>> askedQuestionsBySessionIds(Collection<UUID> sessionIds) {
		return questions.findBySessionIdInAndOrderIndexNotNullOrderByOrderIndex(sessionIds).stream()
				.collect(Collectors.groupingBy(Question::getSessionId, LinkedHashMap::new, Collectors.toList()));
	}

	/** Question id to question. One query. */
	@Transactional(readOnly = true)
	public Map<UUID, Question> questionsByIds(Collection<UUID> questionIds) {
		return questions.findAllById(questionIds).stream()
				.collect(Collectors.toMap(Question::getId, Function.identity()));
	}

	/** Question id to its answer, for the questions that have one. One query. */
	@Transactional(readOnly = true)
	public Map<UUID, Answer> answersByQuestionIds(Collection<UUID> questionIds) {
		return answers.findByQuestionIdIn(questionIds).stream()
				.collect(Collectors.toMap(Answer::getQuestionId, Function.identity()));
	}

	/** Session id to its project id. One query. */
	@Transactional(readOnly = true)
	public Map<UUID, UUID> projectIdsBySessionIds(Collection<UUID> sessionIds) {
		return sessions.findAllById(sessionIds).stream()
				.collect(Collectors.toMap(InterviewSession::getId, InterviewSession::getProjectId));
	}

	private List<InterviewTopic> saveTopics(UUID sessionId, List<PlannedTopic> plan) {
		List<InterviewTopic> rows = new ArrayList<>();
		for (int i = 0; i < plan.size(); i++) {
			PlannedTopic topic = plan.get(i);
			rows.add(new InterviewTopic(sessionId, i + 1, topic.category(), topic.title(), topic.rationale(),
					topic.targetPaths(), topic.targetSymbols()));
		}
		sessions.findById(sessionId).orElseThrow().planned(rows.size());
		return topics.saveAll(rows);
	}

	/** One transaction: the question becomes number 1, its topic ASKED, the session IN_PROGRESS. */
	private Question askFirst(UUID sessionId, UUID topicId, GeneratedQuestion generated) {
		InterviewTopic topic = topics.findById(topicId).orElseThrow();
		Question question = questions.save(Question.prepared(topic, generated.text(), generated.codeRefs(),
				generated.rubric(), generated.idealAnswerOutline(), generated.promptVersion()));
		question.ask(1);
		topic.asked();
		// Hibernate runs the question INSERT before the session UPDATE, so the foreign key to it holds.
		sessions.findById(sessionId).orElseThrow().begin(question.getId());
		return question;
	}

	private InterviewSession fail(UUID sessionId, String reason) {
		return transaction.execute(status -> {
			InterviewSession session = sessions.findById(sessionId).orElseThrow();
			session.fail(reason);
			return session;
		});
	}

	private void abandonIfRunning(UUID sessionId) {
		InterviewSession session = sessions.findById(sessionId).orElseThrow();
		if (session.getStatus() == SessionStatus.IN_PROGRESS) {
			questions.findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(sessionId).forEach(Question::skip);
			session.abandon();
		}
	}

	/**
	 * The report step, after a session ended. Ending and reporting are separate on purpose: the status change
	 * never waits for the model, and a failed REPORT call leaves an ended session without a report, which the next
	 * {@code endInterview} or re-sent last answer retries. Model call outside any transaction, then one short
	 * transaction that attaches the report only if none is there yet.
	 *
	 * @return the error when the report could not be written; null when it was written or is not needed
	 */
	private DomainException writeReportIfMissing(UUID sessionId) {
		InterviewSession session = sessions.findById(sessionId).orElseThrow();
		boolean ended = session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.ABANDONED;
		if (!ended || session.getReport() != null) {
			return null;
		}
		List<TranscriptEntry> transcript = transcript(sessionId);
		if (transcript.stream().noneMatch(entry -> entry.score() != null)) {
			return null;   // nothing was graded: nothing to report on
		}
		String repoName = projectService.context(session.getProjectId()).map(ProjectContext::name).orElse("the project");
		try {
			InterviewReport report = reportWriter.write(session.getProjectId(), sessionId, repoName,
					session.getDifficulty(), transcript);
			transaction.executeWithoutResult(status -> {
				InterviewSession row = sessions.findById(sessionId).orElseThrow();
				if (row.getReport() == null) {
					row.attachReport(report);
				}
			});
			log.info("Report written, overall score {}", report.overallScore());
			return null;
		}
		catch (DomainException ex) {
			log.warn("Report not written: {}", ex.getMessage());
			return ex;
		}
		catch (OptimisticLockingFailureException ex) {
			return null;   // another request attached its report first
		}
	}

	/** Every asked question in order, with its answer only when graded. */
	private List<TranscriptEntry> transcript(UUID sessionId) {
		List<Question> asked = askedQuestions(sessionId);
		Map<UUID, Answer> graded = answers.findByQuestionIdIn(asked.stream().map(Question::getId).toList()).stream()
				.filter(Answer::isEvaluated)
				.collect(Collectors.toMap(Answer::getQuestionId, Function.identity()));
		Map<UUID, Integer> orders = asked.stream().collect(Collectors.toMap(Question::getId, Question::getOrderIndex));
		return asked.stream().map(question -> {
			Answer answer = graded.get(question.getId());
			return new TranscriptEntry(question.getOrderIndex(), question.getCategory(),
					question.getParentQuestionId() == null ? null : orders.get(question.getParentQuestionId()),
					question.getText(), answer == null ? null : answer.getText(),
					answer == null ? null : answer.getScore(), answer == null ? null : answer.getVerdict(),
					answer == null ? null : answer.getMissedPoints());
		}).toList();
	}

	/** The stored answer when the same text comes again; a new one only for the current question of a live session. */
	private Answer existingOrNewAnswer(Question question, InterviewSession session, String text, String textSha) {
		Optional<Answer> existing = answers.findByQuestionId(question.getId());
		if (existing.isPresent()) {
			return sameTextOrConflict(existing.get(), textSha);
		}
		if (session.getStatus() != SessionStatus.IN_PROGRESS) {
			throw new DomainException(ErrorCode.SESSION_NOT_ACTIVE,
					"This interview is " + session.getStatus() + " and takes no more answers.");
		}
		if (!question.getId().equals(session.getCurrentQuestionId())) {
			throw new DomainException(ErrorCode.NOT_CURRENT_QUESTION,
					"This is not the current question. Answer the current question first.", "questionId");
		}
		try {
			return transaction.execute(status -> answers.saveAndFlush(new Answer(question.getId(), text, textSha)));
		}
		catch (DataIntegrityViolationException ex) {
			// Another request saved an answer between our check and our insert (uq_answer_question). Read the winner.
			return sameTextOrConflict(answers.findByQuestionId(question.getId()).orElseThrow(() -> ex), textSha);
		}
	}

	private static Answer sameTextOrConflict(Answer answer, String textSha) {
		if (!answer.getTextSha().equals(textSha)) {
			throw new DomainException(ErrorCode.ALREADY_ANSWERED, "This question already has a different answer.",
					"questionId");
		}
		return answer;
	}

	/** Row lock, check, write: of two requests saving a grade at once, only the first one writes it. */
	private Answer saveEvaluation(UUID answerId, UUID questionId, Evaluation evaluation) {
		Answer answer = answers.findByIdForUpdate(answerId).orElseThrow();
		if (!answer.isEvaluated()) {
			answer.evaluate(evaluation);
			questions.findById(questionId).orElseThrow().answered();
		}
		return answer;
	}

	/**
	 * The next step after a graded answer (LLD §9.2 steps 5-7). Model calls first, without a transaction; then one
	 * transaction that saves the new question, the topic changes and the session's pointer, and only if the session
	 * has not changed since {@code session} was read (its {@code @Version}).
	 */
	private void next(InterviewSession session, Question answered, Answer answer) {
		UUID sessionId = session.getId();
		long version = session.getVersion();
		List<InterviewTopic> planned = topics.findBySessionIdOrderByOrderIndex(sessionId);
		InterviewTopic topic = planned.stream().filter(t -> t.getId().equals(answered.getTopicId())).findFirst()
				.orElseThrow();
		List<InterviewTopic> pending = planned.stream().filter(t -> t.getStatus() == TopicStatus.PENDING).toList();
		Evaluation evaluation = answer.evaluation();

		if (followUpPolicy.shouldFollowUp(evaluation, topic.getFollowUpCount(), session.getLlmCallCount(),
				pending.size())) {
			Optional<GeneratedQuestion> followUp = generator.generateFollowUp(session.getProjectId(), sessionId,
					session.getDifficulty(), answered, answer.getText(), evaluation.missedPoints(),
					evaluation.followUpReason());
			if (followUp.isPresent()) {
				GeneratedQuestion generated = followUp.get();
				inSession(sessionId, version, current -> {
					int order = nextOrder(sessionId);
					Question question = questions.save(Question.followUp(answered, generated.text(),
							generated.codeRefs(), generated.rubric(), generated.idealAnswerOutline(),
							generated.promptVersion()));
					question.ask(order);
					topics.findById(topic.getId()).orElseThrow().followUpAsked();
					current.advance(question.getId());
				});
				return;
			}
		}

		List<String> previous = askedQuestions(sessionId).stream().map(Question::getText).toList();
		for (InterviewTopic nextTopic : pending) {
			Optional<GeneratedQuestion> generated = generator.generate(session.getProjectId(), sessionId,
					session.getDifficulty(), nextTopic, previous);
			if (generated.isPresent()) {
				inSession(sessionId, version, current -> {
					int order = nextOrder(sessionId);
					topics.findById(topic.getId()).orElseThrow().done();
					InterviewTopic upcoming = topics.findById(nextTopic.getId()).orElseThrow();
					Question question = questions.save(Question.prepared(upcoming, generated.get().text(),
							generated.get().codeRefs(), generated.get().rubric(), generated.get().idealAnswerOutline(),
							generated.get().promptVersion()));
					question.ask(order);
					upcoming.asked();
					current.advance(question.getId());
				});
				return;
			}
			transaction.executeWithoutResult(status -> topics.findById(nextTopic.getId()).orElseThrow().skip());
		}

		inSession(sessionId, version, current -> {
			topics.findById(topic.getId()).orElseThrow().done();
			current.complete();   // the report follows, after this transaction (writeReportIfMissing)
		});
		log.info("Interview COMPLETED");
	}

	/** A short transaction that only runs if the session's version is still {@code expectedVersion}. */
	private void inSession(UUID sessionId, long expectedVersion, Consumer<InterviewSession> change) {
		transaction.executeWithoutResult(status -> {
			InterviewSession session = sessions.findById(sessionId).orElseThrow();
			if (session.getVersion() != expectedVersion) {
				throw new OptimisticLockingFailureException("Session " + sessionId + " changed meanwhile");
			}
			change.accept(session);
		});
	}

	private int nextOrder(UUID sessionId) {
		List<Question> asked = questions.findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(sessionId);
		return asked.isEmpty() ? 1 : asked.getLast().getOrderIndex() + 1;
	}

	/** The stored state: the answer, its question, the question asked right after it, the session. */
	private SubmitAnswerResult result(UUID questionId, UUID answerId, DomainException error) {
		return transaction.execute(status -> {
			Question question = questions.findById(questionId).orElseThrow();
			Question next = question.getOrderIndex() == null ? null
					: questions.findBySessionIdAndOrderIndex(question.getSessionId(), question.getOrderIndex() + 1)
							.orElse(null);
			return new SubmitAnswerResult(answers.findById(answerId).orElseThrow(), question, next,
					sessions.findById(question.getSessionId()).orElseThrow(), error);
		});
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);   // every JVM has SHA-256
		}
	}

}
