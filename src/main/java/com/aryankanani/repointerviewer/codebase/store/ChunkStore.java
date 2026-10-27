package com.aryankanani.repointerviewer.codebase.store;

import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes {@code code_chunk} with plain SQL (DECISIONS.md D-004): batch inserts, the {@code vector}
 * column and similarity search are explicit, not hidden behind JPA or Spring AI's VectorStore.
 *
 * <p>Vectors travel as text in pgvector's format, {@code [0.12,-0.03,...]}, and the SQL turns them into a
 * {@code vector} with {@code ?::vector}. That needs no extra library.
 *
 * <p>Every query filters by {@code project_id}: chunks of one repository must never leak into another
 * project's interview.
 */
@Repository
public class ChunkStore {

	/** Rows per JDBC batch: one round trip to Postgres per this many rows instead of one per row. */
	private static final int JDBC_BATCH_SIZE = 500;

	private final JdbcTemplate jdbc;

	ChunkStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Saves the chunks of one file without embeddings ({@code embedding} stays NULL until embedded). */
	@Transactional
	public void insert(UUID projectId, UUID fileId, List<Chunk> chunks) {
		jdbc.batchUpdate("""
				INSERT INTO code_chunk
				    (project_id, file_id, path, chunk_type, symbol, start_line, end_line, content, token_estimate)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", chunks, JDBC_BATCH_SIZE, (statement, chunk) -> {
			statement.setObject(1, projectId);
			statement.setObject(2, fileId);
			statement.setString(3, chunk.path());
			statement.setString(4, chunk.chunkType().name());
			statement.setString(5, chunk.symbol());
			statement.setInt(6, chunk.startLine());
			statement.setInt(7, chunk.endLine());
			statement.setString(8, chunk.content());
			statement.setInt(9, chunk.tokenEstimate());
		});
	}

	/**
	 * Up to {@code limit} chunks of the project that have no embedding yet. No OFFSET: once a chunk is
	 * embedded it no longer matches {@code embedding IS NULL}, so asking again returns the next ones.
	 */
	public List<PendingChunk> findUnembedded(UUID projectId, int limit) {
		return jdbc.query("""
				SELECT id, path, symbol, content
				FROM code_chunk
				WHERE project_id = ? AND embedding IS NULL
				LIMIT ?
				""", (row, i) -> new PendingChunk(row.getObject("id", UUID.class), row.getString("path"),
				row.getString("symbol"), row.getString("content")), projectId, limit);
	}

	/** One short transaction; the model call that produced the vectors happened before, outside of it. */
	@Transactional
	public void saveEmbeddings(List<ChunkEmbedding> embeddings) {
		jdbc.batchUpdate("UPDATE code_chunk SET embedding = ?::vector WHERE id = ?", embeddings, JDBC_BATCH_SIZE,
				(statement, embedding) -> {
					statement.setString(1, toVectorLiteral(embedding.vector()));
					statement.setObject(2, embedding.chunkId());
				});
	}

	/**
	 * The {@code limit} chunks closest in meaning to the query vector, most similar first (LLD §7 step 2).
	 *
	 * <p>{@code <=>} is pgvector's cosine distance: 0 when two vectors point the same way, 1 when they are
	 * unrelated. Similarity is {@code 1 - distance}. Chunks without an embedding and DOC chunks are left out:
	 * a question must point at code or config, not at the README.
	 */
	public List<RetrievedChunk> search(UUID projectId, float[] queryVector, int limit) {
		String query = toVectorLiteral(queryVector);
		return jdbc.query("""
				SELECT id, path, symbol, start_line, end_line, content, token_estimate,
				       1 - (embedding <=> ?::vector) AS similarity
				FROM code_chunk
				WHERE project_id = ? AND embedding IS NOT NULL AND chunk_type <> 'DOC'
				ORDER BY embedding <=> ?::vector
				LIMIT ?
				""", (row, i) -> new RetrievedChunk(row.getObject("id", UUID.class), row.getString("path"),
				row.getString("symbol"), row.getInt("start_line"), row.getInt("end_line"), row.getString("content"),
				row.getInt("token_estimate"), row.getDouble("similarity")), query, projectId, query, limit);
	}

	/** The symbols of the project's TYPE_HEADER chunks: every parsed type, nested ones as {@code Order.Status}. */
	public List<String> typeSymbols(UUID projectId) {
		return jdbc.queryForList("""
				SELECT DISTINCT symbol FROM code_chunk
				WHERE project_id = ? AND chunk_type = 'TYPE_HEADER'
				""", String.class, projectId);
	}

	/** {@code [0.12,-0.03,...]}, the text form pgvector reads. */
	static String toVectorLiteral(float[] vector) {
		StringBuilder text = new StringBuilder(vector.length * 12).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				text.append(',');
			}
			text.append(vector[i]);
		}
		return text.append(']').toString();
	}

}
