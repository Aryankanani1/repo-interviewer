package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.contracts.interview.AnswerDto;
import com.aryankanani.repointerviewer.contracts.interview.AnswerRequest;
import com.aryankanani.repointerviewer.contracts.interview.AnswerVerdict;
import com.aryankanani.repointerviewer.contracts.interview.CodeRef;
import com.aryankanani.repointerviewer.contracts.interview.Difficulty;
import com.aryankanani.repointerviewer.contracts.interview.EndResponse;
import com.aryankanani.repointerviewer.contracts.interview.InterviewApi;
import com.aryankanani.repointerviewer.contracts.interview.QuestionCategory;
import com.aryankanani.repointerviewer.contracts.interview.QuestionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionStatus;
import com.aryankanani.repointerviewer.contracts.interview.StartInterviewRequest;
import com.aryankanani.repointerviewer.contracts.interview.StartResponse;
import com.aryankanani.repointerviewer.contracts.interview.SubmitAnswerResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link InterviewApi} for the GraphQL app's tests (task 4.1): no interview-service, no HTTP, no model.
 * <ul>
 * <li>Reads answer from the records a test added ({@link #add(SessionDto)}, ...), like the real service: unknown ids
 * left out, each project's sessions newest first, each session's asked questions in order.</li>
 * <li>Mutations return what the test queued ({@link #onStart}, {@link #onSubmitAnswer}, {@link #onEnd}), and keep the
 * records in it, so a later read sees them.</li>
 * </ul>
 * Filled with records by each test, not a snapshot of the real service: the app only maps these records to GraphQL,
 * and the interview's rules are interview-service's own tests (DECISIONS.md D-026). Failures are the exceptions the
 * real HTTP client rebuilds ({@code DomainErrorHandler}), or any other one an unexpected response would be.
 */
public class FakeInterviewApi implements InterviewApi {

	public static final String CONTROLLER = "src/main/java/com/example/shop/order/OrderController.java";

	private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");

	private static final AtomicLong clock = new AtomicLong();

	private final Map<UUID, SessionDto> sessions = new ConcurrentHashMap<>();

	private final Map<UUID, QuestionDto> questions = new ConcurrentHashMap<>();

	private final Map<UUID, AnswerDto> answers = new ConcurrentHashMap<>();   // by question id

	private final Queue<StartResponse> starts = new ConcurrentLinkedQueue<>();

	private final Queue<SubmitAnswerResponse> submits = new ConcurrentLinkedQueue<>();

	private final Queue<EndResponse> ends = new ConcurrentLinkedQueue<>();

	private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

	private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

	private final AtomicReference<StartInterviewRequest> lastStart = new AtomicReference<>();

	private final AtomicReference<AnswerRequest> lastAnswer = new AtomicReference<>();

	// --- Records for tests: plausible values, each one later than the one before

	/** An IN_PROGRESS JUNIOR session of 3 questions, with no current question yet. */
	public static SessionDto session(UUID projectId) {
		return new SessionDto(UUID.randomUUID(), projectId, SessionStatus.IN_PROGRESS, Difficulty.JUNIOR, List.of(), 3,
				null, null, next(), null, null);
	}

	/** {@code session} with this question waiting for an answer. */
	public static SessionDto asking(SessionDto session, UUID questionId) {
		return new SessionDto(session.id(), session.projectId(), session.status(), session.difficulty(),
				session.focusAreas(), session.plannedQuestionCount(), session.failureReason(), session.report(),
				session.startedAt(), session.completedAt(), questionId);
	}

	/**
	 * Question {@code order} of the session, citing OrderController lines 27-30.
	 *
	 * @param parentId the question it follows up on; null for a main question
	 */
	public static QuestionDto question(UUID sessionId, int order, UUID parentId) {
		return new QuestionDto(UUID.randomUUID(), sessionId, order, QuestionCategory.ARCHITECTURE, "Question " + order,
				parentId != null, List.of(new CodeRef(CONTROLLER, 27, 30)), next(), parentId);
	}

	/** An answer graded {@code score} (ADEQUATE), with one point covered, one missed and an ideal outline. */
	public static AnswerDto graded(UUID questionId, String text, int score) {
		return new AnswerDto(UUID.randomUUID(), questionId, text, next(), new AnswerDto.Evaluation(score,
				AnswerVerdict.ADEQUATE, "Fine.", List.of("validation"), List.of("error handling"), List.of(),
				List.of("The body is validated"), next()));
	}

	/** An answer that is saved but not graded yet. */
	public static AnswerDto ungraded(UUID questionId, String text) {
		return new AnswerDto(UUID.randomUUID(), questionId, text, next(), null);
	}

	private static Instant next() {
		return FIRST.plusSeconds(clock.incrementAndGet());
	}

	// --- What a test sets up

	public SessionDto add(SessionDto session) {
		sessions.put(session.id(), session);
		return session;
	}

	public QuestionDto add(QuestionDto question) {
		questions.put(question.id(), question);
		return question;
	}

	public AnswerDto add(AnswerDto answer) {
		answers.put(answer.questionId(), answer);
		return answer;
	}

	/** The next {@link #start} returns this; its session and first question are kept. */
	public void onStart(StartResponse response) {
		starts.add(response);
	}

	/** The next {@link #submitAnswer} returns this; its answer, next question and session are kept. */
	public void onSubmitAnswer(SubmitAnswerResponse response) {
		submits.add(response);
	}

	/** The next {@link #end} returns this; its session is kept. */
	public void onEnd(EndResponse response) {
		ends.add(response);
	}

	/** The next call, of any method, throws {@code failure}. */
	public void failNextCall(RuntimeException failure) {
		nextFailure.set(failure);
	}

	/** How many times {@code method} was called since the last {@link #reset()}: one call is one HTTP request. */
	public int calls(String method) {
		AtomicInteger count = calls.get(method);
		return count == null ? 0 : count.get();
	}

	public StartInterviewRequest lastStart() {
		return lastStart.get();
	}

	public AnswerRequest lastAnswer() {
		return lastAnswer.get();
	}

	public void reset() {
		sessions.clear();
		questions.clear();
		answers.clear();
		starts.clear();
		submits.clear();
		ends.clear();
		calls.clear();
		nextFailure.set(null);
		lastStart.set(null);
		lastAnswer.set(null);
	}

	// --- InterviewApi

	@Override
	public StartResponse start(StartInterviewRequest request) {
		called("start");
		lastStart.set(request);
		StartResponse response = queued(starts, "onStart");
		add(response.session());
		if (response.firstQuestion() != null) {
			add(response.firstQuestion());
		}
		return response;
	}

	@Override
	public SubmitAnswerResponse submitAnswer(UUID id, AnswerRequest request) {
		called("submitAnswer");
		lastAnswer.set(request);
		SubmitAnswerResponse response = queued(submits, "onSubmitAnswer");
		add(response.answer());
		add(response.session());
		if (response.nextQuestion() != null) {
			add(response.nextQuestion());
		}
		return response;
	}

	@Override
	public EndResponse end(UUID id) {
		called("end");
		EndResponse response = queued(ends, "onEnd");
		add(response.session());
		return response;
	}

	@Override
	public List<SessionDto> sessions(List<UUID> ids) {
		called("sessions");
		return ids.stream().map(sessions::get).filter(Objects::nonNull).toList();
	}

	@Override
	public List<SessionDto> sessionsByProject(List<UUID> projectIds) {
		called("sessionsByProject");
		return sessions.values().stream().filter(session -> projectIds.contains(session.projectId()))
				.sorted(Comparator.comparing(SessionDto::startedAt).reversed()).toList();
	}

	@Override
	public List<QuestionDto> questions(List<UUID> ids) {
		called("questions");
		return ids.stream().map(questions::get).filter(Objects::nonNull).toList();
	}

	@Override
	public List<QuestionDto> askedQuestions(List<UUID> sessionIds) {
		called("askedQuestions");
		return questions.values().stream().filter(question -> sessionIds.contains(question.sessionId()))
				.sorted(Comparator.comparing(QuestionDto::sessionId).thenComparingInt(QuestionDto::order)).toList();
	}

	@Override
	public List<AnswerDto> answers(List<UUID> questionIds) {
		called("answers");
		return questionIds.stream().map(answers::get).filter(Objects::nonNull).toList();
	}

	private void called(String method) {
		calls.computeIfAbsent(method, name -> new AtomicInteger()).incrementAndGet();
		RuntimeException failure = nextFailure.getAndSet(null);
		if (failure != null) {
			throw failure;
		}
	}

	private static <T> T queued(Queue<T> responses, String setter) {
		T response = responses.poll();
		if (response == null) {
			throw new IllegalStateException("No response queued: call " + setter + "() first");
		}
		return response;
	}

}
