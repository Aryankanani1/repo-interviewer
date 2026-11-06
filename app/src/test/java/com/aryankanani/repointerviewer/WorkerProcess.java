package com.aryankanani.repointerviewer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The real app in a JVM of its own (task 2.7), so a test can kill it the way a crash does: {@link #kill} is SIGKILL,
 * with no shutdown hooks, no offset commit and no goodbye to the consumer group. Started from the test's own
 * classpath without {@code test-classes} (none of the test doubles), in an empty working directory (so no
 * {@code .env} with a real API key is read), and without {@code ANTHROPIC_API_KEY} in its environment.
 */
public final class WorkerProcess implements AutoCloseable {

	private final Process process;

	private WorkerProcess(Process process) {
		this.process = process;
	}

	/** @param log the file that gets the process's output, for when a test fails */
	public static WorkerProcess start(List<String> arguments, Path log) throws IOException {
		String java = ProcessHandle.current().info().command().orElse("java");
		String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.filter(entry -> !entry.endsWith("test-classes"))
				.collect(Collectors.joining(File.pathSeparator));
		List<String> command = new ArrayList<>(List.of(java, "-Xmx512m", "-cp", classpath,
				RepoInterviewerApplication.class.getName()));
		command.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(Files.createTempDirectory("worker").toFile())
				.redirectErrorStream(true)
				.redirectOutput(log.toFile());
		builder.environment().remove("ANTHROPIC_API_KEY");
		return new WorkerProcess(builder.start());
	}

	/** SIGKILL, then waits until the process is gone. */
	public void kill() throws InterruptedException {
		process.destroyForcibly();
		process.waitFor(30, TimeUnit.SECONDS);
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	@Override
	public void close() throws InterruptedException {
		if (process.isAlive()) {
			kill();
		}
	}

}
