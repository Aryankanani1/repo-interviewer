package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.contracts.analysis.ChunkRangesRequest;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkSearchRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for analysis-service on localhost (task 4.1), for an app process whose real {@code AnalysisApi} client is
 * pointed here ({@code spring.http.serviceclient.analysis.base-url}): the answers of a {@link FakeAnalysisApi}, over
 * HTTP. Only what an interview worker asks: chunks for a topic or for line ranges (grading, the next question), and
 * projects by id (the report's name; none here, so "the project").
 */
public final class FakeAnalysisServer implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern CHUNKS = Pattern.compile("/internal/projects/([0-9a-f-]{36})/chunks/(search|ranges)");

	private final FakeAnalysisApi analysis = new FakeAnalysisApi();

	private final HttpServer server;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	public FakeAnalysisServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	private void handle(HttpExchange exchange) throws IOException {
		byte[] body = exchange.getRequestBody().readAllBytes();
		String path = exchange.getRequestURI().getPath();
		Matcher chunks = CHUNKS.matcher(path);
		Object response;
		if (chunks.matches()) {
			UUID project = UUID.fromString(chunks.group(1));
			response = chunks.group(2).equals("search")
					? analysis.searchChunks(project, JSON.readValue(body, ChunkSearchRequest.class))
					: analysis.chunksForRanges(project, JSON.readValue(body, ChunkRangesRequest.class));
		}
		else if (path.equals("/internal/projects")) {
			response = List.of();
		}
		else {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
			return;
		}
		byte[] json = JSON.writeValueAsBytes(response);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, json.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(json);
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

}
