package com.aryankanani.repointerviewer.contracts.interview;

import java.util.UUID;

/**
 * The payload of every event of the answer flow on Kafka (task 2.5, LLD §13.5), and the event types it travels with.
 * Only ids: everything else is in the interview side's database. Written by the interview side; read by its own
 * workers and by the {@code interviewEvents} subscription's fan-out in {@code api} (task 4.1).
 *
 * @param nextQuestionId the question just asked, in QuestionReady only
 */
public record AnswerFlowEvent(UUID answerId, UUID questionId, UUID nextQuestionId) {

	public static final String ANSWER_SUBMITTED = "AnswerSubmitted";

	public static final String ANSWER_EVALUATED = "AnswerEvaluated";

	public static final String QUESTION_READY = "QuestionReady";

	public static final String SESSION_COMPLETED = "SessionCompleted";

	public static final String SESSION_FAILED = "SessionFailed";

}
