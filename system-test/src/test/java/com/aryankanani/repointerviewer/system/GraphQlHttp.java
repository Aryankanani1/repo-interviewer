package com.aryankanani.repointerviewer.system;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway's {@code /graphql} over real HTTP, the way a browser uses it (task 4.1 step 4b): a POST for queries and
 * mutations, and a POST that accepts {@code text/event-stream} for a subscription (DECISIONS.md D-020), each with
 * {@code Authorization: Bearer <token>} (task 4.4).
 */
final class GraphQlHttp implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient http = HttpClient.newHttpClient();

	private final URI endpoint;

	private final String token;

	/** @param token the JWT every request sends; null for none */
	GraphQlHttp(String gatewayUrl, String token) {
		this.endpoint = URI.create(gatewayUrl + "/graphql");
		this.token = token;
	}

	/** The HTTP status of a query, for a request the gateway refuses before GraphQL runs. */
	int status(String document) throws IOException, InterruptedException {
		return http.send(request(document, Map.of(), "application/json").build(),
				HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/** @return the whole response: {@code data}, and {@code errors} when there are any */
	JsonNode execute(String document, Map<String, Object> variables) throws IOException, InterruptedException {
		return execute(document, variables, Map.of());
	}

	/** With extra request headers, for example a W3C {@code traceparent} (task 4.3). */
	JsonNode execute(String document, Map<String, Object> variables, Map<String, String> headers)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = request(document, variables, "application/json").timeout(Duration.ofSeconds(60));
		headers.forEach(request::header);
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		return JSON.readTree(response.body());
	}

	/** Opens the subscription; its events arrive in {@link Subscription#events()} as they come. */
	Subscription subscribe(String document, Map<String, Object> variables) {
		List<JsonNode> events = new CopyOnWriteArrayList<>();
		CompletableFuture<HttpResponse<Stream<String>>> response = http.sendAsync(
				request(document, variables, "text/event-stream").build(), HttpResponse.BodyHandlers.ofLines());
		CompletableFuture<Void> reading = response.thenAcceptAsync(open -> open.body()
				.filter(line -> line.startsWith("data:"))
				.forEach(line -> events.add(JSON.readTree(line.substring("data:".length())))));
		return new Subscription(response, reading, events);
	}

	private HttpRequest.Builder request(String document, Map<String, Object> variables, String accept) {
		String body = JSON.writeValueAsString(Map.of("query", document, "variables", variables));
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
				.header("Accept", accept).POST(HttpRequest.BodyPublishers.ofString(body));
		return token == null ? request : request.header("Authorization", "Bearer " + token);
	}

	@Override
	public void close() {
		http.shutdownNow();
	}

	/** An open subscription: one {@code data} payload per event. */
	record Subscription(CompletableFuture<HttpResponse<Stream<String>>> response, CompletableFuture<Void> reading,
			List<JsonNode> events) {

		/** Until the gateway answered the request, so the subscription is open before anything happens. */
		void awaitOpen() throws ExecutionException, InterruptedException, TimeoutException {
			HttpResponse<Stream<String>> open = response.get(30, TimeUnit.SECONDS);
			if (open.statusCode() != 200) {
				throw new IllegalStateException("Subscription refused: HTTP " + open.statusCode());
			}
		}

	}

}
