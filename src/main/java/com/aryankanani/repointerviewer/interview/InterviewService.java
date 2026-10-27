package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.QuestionCategory;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.interview.planning.QuestionPlanner;
import com.aryankanani.repointerviewer.interview.question.GeneratedQuestion;
import com.aryankanani.repointerviewer.interview.question.QuestionGenerator;
import com.aryankanani.repointerviewer.llm.output.PlannedTopic;
import com.aryankanani.repointerviewer.project.AnalysisStatus;
import com.aryankanani.repointerviewer.project.ProjectContext;
import com.aryankanani.repointerviewer.project.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Interview use cases (LLD §9). {@link #start} in task 1.10; answering and the report in 1.11 and 1.12.
 *
 * <p>Never holds a database transaction across a model call (CLAUDE.md): short transaction → model call with no
 * transaction → short transaction. {@code start} itself is therefore not {@code @Transactional}.
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

	private final QuestionPlanner planner;

	private final QuestionGenerator generator;

	private final TransactionTemplate transaction;

	private final AppProperties.Interview settings;

	InterviewService(ProjectService projectService, InterviewSessionRepository sessions,
			InterviewTopicRepository topics, QuestionRepository questions, QuestionPlanner planner,
			QuestionGenerator generator, PlatformTransactionManager transactionManager, AppProperties properties) {
		this.projectService = projectService;
		this.sessions = sessions;
		this.topics = topics;
		this.questions = questions;
		this.planner = planner;
		this.generator = generator;
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

	@Transactional(readOnly = true)
	public Optional<InterviewSession> find(UUID sessionId) {
		return sessions.findById(sessionId);
	}

	/** Newest first. */
	@Transactional(readOnly = true)
	public List<InterviewSession> sessionsOf(UUID projectId) {
		return sessions.findByProjectIdOrderByStartedAtDesc(projectId);
	}

	/** The questions shown so far, in order. Prepared (prefetched) ones are never returned. */
	@Transactional(readOnly = true)
	public List<Question> askedQuestions(UUID sessionId) {
		return questions.findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(sessionId);
	}

	@Transactional(readOnly = true)
	public Optional<Question> question(UUID questionId) {
		return questions.findById(questionId);
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

}
