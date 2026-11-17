package com.aryankanani.repointerviewer.system;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One app's real jar in a JVM of its own, as it is deployed (task 4.1 step 4b): {@code java -jar}, none of the test
 * classes, an empty working directory (so no {@code .env} with a real API key is read) and no
 * {@code ANTHROPIC_API_KEY} in its environment. Its output goes to {@code target/system-<module>.log}.
 */
final class AppProcess implements AutoCloseable {

	private final String module;

	private final Process process;

	private final int port;

	private final Path log;

	private AppProcess(String module, Process process, int port, Path log) {
		this.module = module;
		this.process = process;
		this.port = port;
		this.log = log;
	}

	/** @param arguments Spring Boot command-line arguments, which win over every application*.yml */
	static AppProcess start(String module, List<String> arguments) throws IOException {
		Path jar = jar(module);
		int port = freePort();
		String java = ProcessHandle.current().info().command().orElse("java");
		List<String> command = new ArrayList<>(List.of(java, "-Xmx384m", "-jar", jar.toString(),
				"--server.port=" + port));
		command.addAll(arguments);
		Path log = Path.of("target", "system-" + module + ".log").toAbsolutePath();
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(Files.createTempDirectory(module).toFile())
				.redirectErrorStream(true)
				.redirectOutput(log.toFile());
		builder.environment().remove("ANTHROPIC_API_KEY");
		return new AppProcess(module, builder.start(), port, log);
	}

	String url() {
		return "http://localhost:" + port;
	}

	/** Until its readiness probe says UP; fails at once if the process died, with the end of its log. */
	void awaitReady(Duration timeout) throws IOException, InterruptedException {
		Instant deadline = Instant.now().plus(timeout);
		try (HttpClient http = HttpClient.newHttpClient()) {
			HttpRequest readiness = HttpRequest.newBuilder(URI.create(url() + "/actuator/health/readiness"))
					.timeout(Duration.ofSeconds(2)).build();
			while (Instant.now().isBefore(deadline)) {
				if (!process.isAlive()) {
					throw new IllegalStateException(module + " exited with " + process.exitValue() + ":\n" + logTail());
				}
				try {
					if (http.send(readiness, HttpResponse.BodyHandlers.ofString()).body().contains("\"UP\"")) {
						return;
					}
				}
				catch (IOException notListeningYet) {
					// Still starting.
				}
				Thread.sleep(500);
			}
		}
		throw new IllegalStateException(module + " not ready after " + timeout + ":\n" + logTail());
	}

	/** SIGTERM, a graceful stop, as when Kubernetes stops a pod. */
	void stop() throws InterruptedException {
		process.destroy();
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
		}
	}

	@Override
	public void close() throws InterruptedException {
		if (process.isAlive()) {
			stop();
		}
	}

	private String logTail() throws IOException {
		List<String> lines = Files.readAllLines(log);
		return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
	}

	/** The jar Maven packaged earlier in this build ({@code system.root} and {@code system.version}: Failsafe). */
	private static Path jar(String module) {
		Path jar = Path.of(System.getProperty("system.root", ".."), module, "target",
				module + "-" + System.getProperty("system.version", "0.0.1-SNAPSHOT") + ".jar");
		if (!Files.isRegularFile(jar)) {
			throw new IllegalStateException(jar + " is missing: run ./mvnw verify, which packages the apps first");
		}
		return jar;
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
