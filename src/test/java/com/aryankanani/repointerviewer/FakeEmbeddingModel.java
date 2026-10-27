package com.aryankanani.repointerviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Test {@link EmbeddingModel} (DECISIONS.md D-012): no Ollama, no network, and the same vector for the same
 * text on every run.
 *
 * <p>How it makes a vector (the "hashing trick"): drop the {@code search_document:} / {@code search_query:}
 * prefix, split camelCase ({@code RefundRequest} becomes {@code refund request}), lowercase, and split into
 * words. Each distinct word adds 1 to one of the 768 slots, picked by its hash. Then the vector is scaled to
 * length 1. Two texts that share words share slots, so their cosine similarity is higher.
 *
 * <p>It only counts shared words; it knows nothing about meaning. Tests with it prove the storage and search
 * machinery works. How good the search really is gets measured with the real model in the evals.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

	public static final int DIMENSIONS = 768;

	private static final Pattern TASK_PREFIX = Pattern.compile("^search_(query|document): ");

	private static final Pattern CAMEL_CASE = Pattern.compile("([a-z0-9])([A-Z])");

	private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");

	private final int dimensions;

	private final AtomicInteger calls = new AtomicInteger();

	private volatile int failOnCall;

	public FakeEmbeddingModel() {
		this(DIMENSIONS);
	}

	/** A model with another vector size, to test what happens when it does not match the column. */
	public FakeEmbeddingModel(int dimensions) {
		this.dimensions = dimensions;
	}

	/** Makes call number {@code callNumber} (1-based) throw, like Ollama going down halfway. */
	public void failOnCall(int callNumber) {
		this.failOnCall = callNumber;
	}

	/** How many times the model was called. One call embeds a whole batch of texts. */
	public int calls() {
		return calls.get();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		int call = calls.incrementAndGet();
		if (call == failOnCall) {
			throw new IllegalStateException("Simulated embedding failure on call " + call);
		}
		List<String> texts = request.getInstructions();
		List<Embedding> embeddings = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			embeddings.add(new Embedding(vector(texts.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	@Override
	public float[] embed(Document document) {
		return embed(document.getText());
	}

	@Override
	public int dimensions() {
		return dimensions;
	}

	private float[] vector(String text) {
		String words = CAMEL_CASE.matcher(TASK_PREFIX.matcher(text).replaceFirst("")).replaceAll("$1 $2")
				.toLowerCase(Locale.ROOT);
		Set<String> distinct = new HashSet<>(Arrays.asList(NOT_A_WORD.split(words)));
		distinct.remove("");
		float[] vector = new float[dimensions];
		for (String word : distinct) {
			vector[Math.floorMod(word.hashCode(), dimensions)] += 1;
		}
		double length = 0;
		for (float value : vector) {
			length += value * value;
		}
		if (length > 0) {
			for (int i = 0; i < dimensions; i++) {
				vector[i] /= (float) Math.sqrt(length);
			}
		}
		return vector;
	}

}
