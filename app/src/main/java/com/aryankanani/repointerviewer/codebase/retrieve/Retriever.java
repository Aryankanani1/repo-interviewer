package com.aryankanani.repointerviewer.codebase.retrieve;

import com.aryankanani.repointerviewer.codebase.embed.EmbeddingService;
import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.RetrievedChunk;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Finds the code a question should be about (LLD §7, DECISIONS.md D-006). Retrieval-augmented generation: the
 * model never sees the whole repository, only these chunks.
 * <ol>
 * <li><b>Exact hits:</b> the files and symbols the plan named ({@link ChunkStore#exactHits}). Asked for by name,
 * so they come first and are always kept.</li>
 * <li><b>Search by meaning:</b> the {@code app.retrieval.top-k} (8) chunks closest to the topic text
 * ({@link ChunkStore#search}), without TEST files unless the topic is about testing.</li>
 * <li>Merged in that order, each chunk once, and added while they fit in the token budget. A chunk that does
 * not fit is skipped and smaller ones after it may still fit. At least one chunk, even if it alone is over.</li>
 * </ol>
 */
@Component
public class Retriever {

	private final ChunkStore chunkStore;

	private final EmbeddingService embeddingService;

	private final int topK;

	@Autowired
	Retriever(ChunkStore chunkStore, EmbeddingService embeddingService, AppProperties properties) {
		this(chunkStore, embeddingService, properties.retrieval().topK());
	}

	Retriever(ChunkStore chunkStore, EmbeddingService embeddingService, int topK) {
		this.chunkStore = chunkStore;
		this.embeddingService = embeddingService;
		this.topK = topK;
	}

	public RetrievedContext forTopic(UUID projectId, TopicQuery query, int maxTokens) {
		Map<UUID, RetrievedChunk> merged = new LinkedHashMap<>();
		chunkStore.exactHits(projectId, query.targetPaths(), query.targetSymbols())
				.forEach(chunk -> merged.putIfAbsent(chunk.id(), chunk));
		chunkStore.search(projectId, embeddingService.embedQuery(query.text()), topK, query.includeTests())
				.forEach(chunk -> merged.putIfAbsent(chunk.id(), chunk));
		return withinBudget(merged, maxTokens);
	}

	/**
	 * The code that given line ranges point at ({@link ChunkStore#chunksOverlapping}), in the order of the ranges:
	 * grading an answer and writing a follow-up see the same code the question was about. No search by meaning.
	 */
	public RetrievedContext forRanges(UUID projectId, List<LineRange> ranges, int maxTokens) {
		Map<UUID, RetrievedChunk> merged = new LinkedHashMap<>();
		for (LineRange range : ranges) {
			chunkStore.chunksOverlapping(projectId, range.path(), range.startLine(), range.endLine())
					.forEach(chunk -> merged.putIfAbsent(chunk.id(), chunk));
		}
		return withinBudget(merged, maxTokens);
	}

	private static RetrievedContext withinBudget(Map<UUID, RetrievedChunk> merged, int maxTokens) {
		List<RetrievedChunk> selected = new ArrayList<>();
		int total = 0;
		for (RetrievedChunk chunk : merged.values()) {
			if (selected.isEmpty() || total + chunk.tokenEstimate() <= maxTokens) {
				selected.add(chunk);
				total += chunk.tokenEstimate();
			}
		}
		return new RetrievedContext(selected, total);
	}

}
