package com.aryankanani.repointerviewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the Anthropic Messages API and Ollama's embeddings on localhost (task 2.7), for an app process whose
 * real {@code SpringAiLlmGateway} and embedding client are pointed here ({@code spring.ai.anthropic.base-url},
 * {@code spring.ai.ollama.base-url}): no network, no cost. It answers EVALUATION and QUESTION requests (told apart by
 * the output schema's field names) with canned JSON about the fixture repository, and Ollama's {@code /api/embed}
 * with constant 768-dimension vectors (retrieval for the next question embeds its query). It can hold the first
 * EVALUATION request open, so a test knows a worker is in the middle of that call.
 */
public final class FakeModelServer implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, Object> EVALUATION = Map.of("score", 9, "coveredPoints", List.of("Lost update"),
			"missedPoints", List.of(), "incorrectClaims", List.of(), "feedback", "Clear.", "followUpNeeded", false,
			"followUpReason", "");

	private static final Map<String, Object> QUESTION = Map.of(
			"question", "OrderService#refund checks what is left to refund. Where does that amount come from?",
			"codeRefs", List.of(Map.of("path", "src/main/java/com/example/shop/order/OrderService.java",
					"startLine", 27, "endLine", 42)),
			"rubric", Map.of("mustMention", List.of("refundedAmount"), "goodToMention", List.of(),
					"redFlags", List.of()),
			"idealAnswerOutline", List.of("It is stored on the order"));

	private final HttpServer server;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final AtomicInteger evaluationRequests = new AtomicInteger();

	private final AtomicInteger questionRequests = new AtomicInteger();

	private final AtomicInteger otherRequests = new AtomicInteger();

	private final CountDownLatch evaluationArrived = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private volatile boolean holdFirstEvaluation;

	public FakeModelServer() throws IOException {
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

	public int evaluationRequests() {
		return evaluationRequests.get();
	}

	public int questionRequests() {
		return questionRequests.get();
	}

	public int otherRequests() {
		return otherRequests.get();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		if (exchange.getRequestURI().getPath().equals("/api/embed")) {
			JsonNode input = JSON.readTree(body).path("input");
			float[] vector = new float[768];
			Arrays.fill(vector, 0.01f);
			respond(exchange, JSON.writeValueAsBytes(Map.of("model", "nomic-embed-text", "embeddings",
					Collections.nCopies(input.isArray() ? input.size() : 1, vector))));
			return;
		}
		Map<String, Object> output;
		if (body.contains("followUpNeeded")) {
			int number = evaluationRequests.incrementAndGet();
			if (number == 1 && holdFirstEvaluation) {
				evaluationArrived.countDown();
				try {
					release.await(120, TimeUnit.SECONDS);   // the caller is killed meanwhile
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			output = EVALUATION;
		}
		else if (body.contains("codeRefs")) {
			questionRequests.incrementAndGet();
			output = QUESTION;
		}
		else {
			otherRequests.incrementAndGet();
			exchange.sendResponseHeaders(400, -1);
			exchange.close();
			return;
		}
		respond(exchange, JSON.writeValueAsBytes(Map.of(
				"id", "msg_fake_" + System.nanoTime(),
				"type", "message",
				"role", "assistant",
				"model", "claude-sonnet-5",
				"content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(output))),
				"stop_reason", "end_turn",
				"usage", Map.of("input_tokens", 120, "output_tokens", 40))));
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
