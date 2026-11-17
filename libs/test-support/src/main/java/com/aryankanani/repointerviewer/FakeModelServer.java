package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.llm.LlmPurpose;
import com.aryankanani.repointerviewer.llm.output.CodeRefOutput;
import com.aryankanani.repointerviewer.llm.output.QuestionOutput;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the Anthropic Messages API and Ollama's embeddings on localhost (tasks 2.7 and 4.1), for app
 * processes whose real {@code SpringAiLlmGateway} and embedding model are pointed here
 * ({@code spring.ai.anthropic.base-url}, {@code spring.ai.ollama.base-url}): no network, no cost.
 * <ul>
 * <li>Chat: tells the purpose apart by the output schema's field names in the request and answers with
 * {@link CannedLlmOutputs}, the same as {@link FakeLlmGateway}. A question cites the first {@code code_chunk} of its
 * prompt, so it passes the real {@code CodeRefValidator} whatever was retrieved.</li>
 * <li>Embeddings ({@code /api/embed}): one constant vector for every text. Code is found by its exact paths and
 * symbols first, so the order of the rest does not matter here.</li>
 * </ul>
 * It can hold the first EVALUATION request open, so a test knows a worker is in the middle of that call.
 */
public final class FakeModelServer implements AutoCloseable {

	public static final int DIMENSIONS = 768;

	/** What {@code /api/embed} answers for every text, and what a test stores for chunks it writes itself. */
	public static final float[] VECTOR = constantVector();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A field name only one purpose's output schema has, checked in this order (QUESTION's is also FOLLOW_UP's). */
	private static final List<Map.Entry<String, LlmPurpose>> SCHEMA_FIELDS = List.of(
			Map.entry("followUpNeeded", LlmPurpose.EVALUATION), Map.entry("topicsToReview", LlmPurpose.REPORT),
			Map.entry("targetPaths", LlmPurpose.PLAN), Map.entry("architectureStyle", LlmPurpose.SUMMARY),
			Map.entry("codeRefs", LlmPurpose.QUESTION));

	private static final Pattern FIRST_CHUNK = Pattern.compile("<code_chunk path=\"([^\"]+)\" lines=\"(\\d+)-(\\d+)\"");

	private final HttpServer server;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final Map<LlmPurpose, AtomicInteger> requests = new EnumMap<>(LlmPurpose.class);

	private final AtomicInteger embedRequests = new AtomicInteger();

	private final AtomicInteger otherRequests = new AtomicInteger();

	private final CountDownLatch evaluationArrived = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private volatile boolean holdFirstEvaluation;

	public FakeModelServer() throws IOException {
		for (LlmPurpose purpose : LlmPurpose.values()) {
			requests.put(purpose, new AtomicInteger());
		}
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	/** The first EVALUATION request gets no answer until {@link #release()}; later ones are answered at once. */
	public void holdFirstEvaluation() {
		holdFirstEvaluation = true;
	}

	/** @return true when the first EVALUATION request arrived within the time */
	public boolean awaitEvaluationRequest(long seconds) throws InterruptedException {
		return evaluationArrived.await(seconds, TimeUnit.SECONDS);
	}

	public void release() {
		release.countDown();
	}

	/** Chat requests for {@code purpose}; FOLLOW_UP is counted as QUESTION (same output schema). */
	public int requests(LlmPurpose purpose) {
		return requests.get(purpose).get();
	}

	public int evaluationRequests() {
		return requests(LlmPurpose.EVALUATION);
	}

	public int embedRequests() {
		return embedRequests.get();
	}

	/** Requests this server could not tell apart: answered 400. */
	public int otherRequests() {
		return otherRequests.get();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (exchange.getRequestURI().getPath().equals("/api/embed")) {
			embedRequests.incrementAndGet();
			JsonNode input = JSON.readTree(body).path("input");
			respond(exchange, JSON.writeValueAsBytes(Map.of("model", "nomic-embed-text", "embeddings",
					Collections.nCopies(input.isArray() ? input.size() : 1, VECTOR))));
			return;
		}
		LlmPurpose purpose = purpose(body);
		if (purpose == null) {
			otherRequests.incrementAndGet();
			exchange.sendResponseHeaders(400, -1);
			exchange.close();
			return;
		}
		int number = requests.get(purpose).incrementAndGet();
		if (purpose == LlmPurpose.EVALUATION && number == 1 && holdFirstEvaluation) {
			evaluationArrived.countDown();
			try {
				release.await(120, TimeUnit.SECONDS);   // the caller is killed meanwhile
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		Object output = purpose == LlmPurpose.QUESTION ? grounded(body) : CannedLlmOutputs.of(purpose);
		respond(exchange, JSON.writeValueAsBytes(Map.of(
				"id", "msg_fake_" + System.nanoTime(),
				"type", "message",
				"role", "assistant",
				"model", "claude-sonnet-5",
				"content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(output))),
				"stop_reason", "end_turn",
				"usage", Map.of("input_tokens", 120, "output_tokens", 40))));
	}

	private static LlmPurpose purpose(String body) {
		return SCHEMA_FIELDS.stream().filter(field -> body.contains(field.getKey())).findFirst()
				.map(Map.Entry::getValue).orElse(null);
	}

	/** The canned question, citing the first code chunk of the prompt (the canned ref when there is none). */
	private static QuestionOutput grounded(String body) {
		QuestionOutput canned = (QuestionOutput) CannedLlmOutputs.of(LlmPurpose.QUESTION);
		Matcher chunk = FIRST_CHUNK.matcher(body.replace("\\\"", "\""));
		if (!chunk.find()) {
			return canned;
		}
		return new QuestionOutput(canned.question(), List.of(new CodeRefOutput(chunk.group(1),
				Integer.parseInt(chunk.group(2)), Integer.parseInt(chunk.group(3)))), canned.rubric(),
				canned.idealAnswerOutline());
	}

	private static float[] constantVector() {
		float[] vector = new float[DIMENSIONS];
		Arrays.fill(vector, 0.01f);
		return vector;
	}

	private static void respond(HttpExchange exchange, byte[] response) {
		try {
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(response);
			}
		}
		catch (IOException callerGone) {
			// The held request's caller was killed: nobody is listening any more.
		}
	}

	@Override
	public void close() {
		release.countDown();
		server.stop(0);
		executor.shutdownNow();
	}

}
