package com.aryankanani.repointerviewer.codebase.embed;

import com.aryankanani.repointerviewer.codebase.store.ChunkEmbedding;
import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.PendingChunk;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns chunks and search text into vectors (LLD §6.8). The only class that talks to the embedding model
 * (Ollama {@code nomic-embed-text} in dev, {@code FakeEmbeddingModel} in tests; ArchitectureTest enforces it).
 *
 * <p>Deliberately not {@code @Transactional}: the model call goes over the network, and a transaction must
 * never stay open across one. Each batch is read, embedded, then saved in its own short transaction.
 */
@Service
public class EmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

	private final ChunkStore chunkStore;

	private final EmbeddingModel embeddingModel;

	private final int batchSize;

	private final int dimensions;

	@Autowired
	EmbeddingService(ChunkStore chunkStore, EmbeddingModel embeddingModel, AppProperties properties) {
		this(chunkStore, embeddingModel, properties.embedding().batchSize(), properties.embedding().dimensions());
	}

	EmbeddingService(ChunkStore chunkStore, EmbeddingModel embeddingModel, int batchSize, int dimensions) {
		this.chunkStore = chunkStore;
		this.embeddingModel = embeddingModel;
		this.batchSize = batchSize;
		this.dimensions = dimensions;
	}

	/**
	 * Embeds every chunk of the project that has no embedding yet, {@code batchSize} at a time.
	 *
	 * <p>Resumable: if it stops halfway (crash, Ollama down), the saved batches stay saved, and the next call
	 * continues with the chunks that are still NULL. Calling it when nothing is left does nothing.
	 *
	 * @return the number of chunks embedded by this call
	 */
	public int embedPending(UUID projectId) {
		int embedded = 0;
		int batches = 0;
		List<PendingChunk> batch;
		while (!(batch = chunkStore.findUnembedded(projectId, batchSize)).isEmpty()) {
			List<String> texts = batch.stream().map(EmbeddingText::document).toList();
			List<float[]> vectors = embeddingModel.embed(texts);
			chunkStore.saveEmbeddings(pair(batch, vectors));
			embedded += batch.size();
			batches++;
		}
		log.debug("Embedded {} chunks in {} batches", embedded, batches);
		return embedded;
	}

	/** The vector to search with: same model, but with the {@code search_query:} prefix. */
	public float[] embedQuery(String text) {
		float[] vector = embeddingModel.embed(EmbeddingText.query(text));
		checkDimensions(vector);
		return vector;
	}

	private List<ChunkEmbedding> pair(List<PendingChunk> batch, List<float[]> vectors) {
		if (vectors.size() != batch.size()) {
			throw new IllegalStateException(
					"Embedding model returned " + vectors.size() + " vectors for " + batch.size() + " texts");
		}
		List<ChunkEmbedding> embeddings = new ArrayList<>(batch.size());
		for (int i = 0; i < batch.size(); i++) {
			checkDimensions(vectors.get(i));
			embeddings.add(new ChunkEmbedding(batch.get(i).id(), vectors.get(i)));
		}
		return embeddings;
	}

	/**
	 * Fails with a clear message instead of Postgres' "expected 768 dimensions". A different size means the
	 * configured model is not the one the {@code vector(768)} column was made for (DECISIONS.md D-005).
	 */
	private void checkDimensions(float[] vector) {
		if (vector.length != dimensions) {
			throw new IllegalStateException("Embedding model returned " + vector.length
					+ " dimensions, but app.embedding.dimensions and the code_chunk.embedding column expect "
					+ dimensions + ". Check spring.ai.ollama.embedding.options.model.");
		}
	}

}
