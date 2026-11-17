package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.llm.LlmGateway;
import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.LlmRequest;
import com.aryankanani.repointerviewer.llm.output.LlmOutput;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test {@link LlmGateway} (DECISIONS.md D-012): no network, no cost. Returns a canned output per purpose, about
 * the fixture repository ({@link CannedLlmOutputs}, which {@code FakeModelServer} answers too), unless a test queued
 * something else: a specific output (also an invalid one, like a plan with invented paths) or a failure. Outputs are
 * normalized like the real gateway does. Every request is recorded, so tests can check how many calls were made.
 *
 * <p>Shared by all integration tests ({@code @Primary} in {@link TestDoublesConfiguration}): call {@link #reset}
 * in {@code @BeforeEach}.
 */
public class FakeLlmGateway implements LlmGateway {

	private final Map<LlmPurpose, Deque<Object>> queued = new EnumMap<>(LlmPurpose.class);

	private final Map<LlmPurpose, Runnable> duringNextCall = new EnumMap<>(LlmPurpose.class);

	private final List<LlmRequest<?>> requests = new CopyOnWriteArrayList<>();

	public synchronized void reset() {
		queued.clear();
		duringNextCall.clear();
		requests.clear();
	}

	/** The next call for this purpose returns {@code output}; later calls fall back to the canned one. */
	public synchronized void enqueue(LlmPurpose purpose, LlmOutput<?> output) {
		queued.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(output);
	}

	/** The next call for this purpose throws {@code failure}, for example an {@code LlmUnavailableException}. */
	public synchronized void enqueueFailure(LlmPurpose purpose, RuntimeException failure) {
		queued.computeIfAbsent(purpose, key -> new ArrayDeque<>()).add(failure);
	}

	/**
	 * The next call for this purpose runs {@code action} before it returns, as if something else happened while the
	 * model was writing: for example a prefetch racing {@code submitAnswer} (task 1.16). The call takes its own output
	 * first, so calls made by {@code action} get the outputs queued after it.
	 */
	public synchronized void duringNextCall(LlmPurpose purpose, Runnable action) {
		duringNextCall.put(purpose, action);
	}

	public List<LlmRequest<?>> requests() {
		return List.copyOf(requests);
	}

	public long calls(LlmPurpose purpose) {
		return requests.stream().filter(request -> request.purpose() == purpose).count();
	}

	@Override
	public <T extends LlmOutput<T>> T call(LlmRequest<T> request) {
		requests.add(request);
		Object next = next(request.purpose());
		Runnable action = takeDuringNextCall(request.purpose());
		if (action != null) {
			action.run();
		}
		if (next instanceof RuntimeException failure) {
			throw failure;
		}
		return request.outputType().cast(next).normalized();
	}

	@Override
	public String promptVersion(LlmPurpose purpose) {
		return purpose.template() + "@fake0000";
	}

	private synchronized Object next(LlmPurpose purpose) {
		Deque<Object> queue = queued.get(purpose);
		return queue == null || queue.isEmpty() ? CannedLlmOutputs.of(purpose) : queue.poll();
	}

	private synchronized Runnable takeDuringNextCall(LlmPurpose purpose) {
		return duringNextCall.remove(purpose);
	}

}
