package com.aryankanani.repointerviewer.interview;

import com.aryankanani.repointerviewer.common.config.AppProperties;
import com.aryankanani.repointerviewer.interview.question.GeneratedQuestion;
import com.aryankanani.repointerviewer.interview.question.QuestionGenerator;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the next topic's main question while the candidate answers the current one (LLD §9.4, task 1.16), and saves
 * it as PREPARED: hidden, with no order, until {@link InterviewService} asks it. The answer then waits only for its
 * grade, not for a new question.
 *
 * <p>Best effort. A failure is only logged: the topic stays PENDING and {@code submitAnswer} writes its question as
 * before. Same pattern as every model call: reads, the model call with no transaction, then one short transaction
 * that locks the topic row and checks again. {@code submitAnswer} locks the same row, so the two never both save a
 * main question for one topic; the unique index {@code uq_question_main_per_topic} is only the backstop.
 */
@Component
class QuestionPrefetcher {

	private static final Logger log = LoggerFactory.getLogger(QuestionPrefetcher.class);

	private final InterviewSessionRepository sessions;

	private final InterviewTopicRepository topics;

	private final QuestionRepository questions;

	private final QuestionGenerator generator;

	private final TransactionTemplate transaction;

	private final boolean enabled;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	QuestionPrefetcher(InterviewSessionRepository sessions, InterviewTopicRepository topics,
			QuestionRepository questions, QuestionGenerator generator, PlatformTransactionManager transactionManager,
			AppProperties properties) {
		this.sessions = sessions;
		this.topics = topics;
		this.questions = questions;
		this.generator = generator;
		this.transaction = new TransactionTemplate(transactionManager);
		this.enabled = properties.interview().prefetchEnabled();
	}

	/** Returns at once. Runs {@link #prepare} on a virtual thread when {@code app.interview.prefetch-enabled} is on. */
	void prefetchNext(UUID sessionId) {
		if (enabled) {
			executor.submit(() -> prepare(sessionId));
		}
	}

	/**
	 * The prefetch itself, on the calling thread: the first PENDING topic gets its main question, if it has none yet
	 * and the session is still running. Never throws.
	 */
	void prepare(UUID sessionId) {
		MDC.put("sessionId", sessionId.toString());
		try {
			InterviewSession session = sessions.findById(sessionId).orElseThrow();
			Optional<InterviewTopic> next = topics.findBySessionIdOrderByOrderIndex(sessionId).stream()
					.filter(topic -> topic.getStatus() == TopicStatus.PENDING).findFirst();
			if (session.getStatus() != SessionStatus.IN_PROGRESS || next.isEmpty()
					|| questions.findByTopicIdAndFollowUpFalse(next.get().getId()).isPresent()) {
				return;
			}
			InterviewTopic topic = next.get();
			List<String> previous = questions.findBySessionIdAndOrderIndexNotNullOrderByOrderIndex(sessionId).stream()
					.map(Question::getText).toList();
			Optional<GeneratedQuestion> generated = generator.generate(session.getProjectId(), sessionId,
					session.getDifficulty(), topic, previous);
			if (generated.isEmpty()) {
				log.info("Prefetch: no grounded question for topic {}; it is written when needed", topic.getOrderIndex());
				return;
			}
			if (transaction.execute(status -> savePrepared(topic.getId(), generated.get()))) {
				log.info("Prefetched the question for topic {}", topic.getOrderIndex());
			}
		}
		catch (RuntimeException ex) {
			log.warn("Prefetch failed; the question is written when needed: {}", ex.toString());
		}
		finally {
			MDC.remove("sessionId");
		}
	}

	/** Under the topic's row lock: saves only if the topic still has no main question and the interview runs. */
	private boolean savePrepared(UUID topicId, GeneratedQuestion generated) {
		InterviewTopic topic = topics.findByIdForUpdate(topicId).orElseThrow();
		InterviewSession session = sessions.findById(topic.getSessionId()).orElseThrow();
		if (topic.getStatus() != TopicStatus.PENDING || session.getStatus() != SessionStatus.IN_PROGRESS
				|| questions.findByTopicIdAndFollowUpFalse(topicId).isPresent()) {
			return false;   // submitAnswer got there first, or the interview ended meanwhile: discard ours
		}
		questions.save(Question.prepared(topic, generated.text(), generated.codeRefs(), generated.rubric(),
				generated.idealAnswerOutline(), generated.promptVersion()));
		return true;
	}

	/** Like the analysis pipeline: a prefetch cut off on shutdown only means the question is written when needed. */
	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

}
