package com.aryankanani.repointerviewer.codebase.store;

import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
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
		return search(projectId, queryVector, limit, true);
	}

	/**
	 * Same, and with {@code includeTests = false} also without chunks of TEST files (role from {@code code_file}):
	 * a question about production code should not be built on its tests.
	 */
	public List<RetrievedChunk> search(UUID projectId, float[] queryVector, int limit, boolean includeTests) {
		String query = toVectorLiteral(queryVector);
		return jdbc.query("""
				SELECT c.id, c.path, c.symbol, c.start_line, c.end_line, c.content, c.token_estimate,
				       1 - (c.embedding <=> ?::vector) AS similarity
				FROM code_chunk c JOIN code_file f ON f.id = c.file_id
				WHERE c.project_id = ? AND c.embedding IS NOT NULL AND c.chunk_type <> 'DOC'
				  AND (? OR f.role <> 'TEST')
				ORDER BY c.embedding <=> ?::vector
				LIMIT ?
				""", ChunkStore::retrievedChunk, query, projectId, includeTests, query, limit);
	}

	/**
	 * The chunks a topic names explicitly (LLD §7 step 1), most specific first:
	 * <ol>
	 * <li>the named methods ({@code OrderService#refund}, with its overloads and {@code #part2} windows);</li>
	 * <li>the TYPE_HEADER of every named file and class;</li>
	 * <li>the other chunks of those files and classes, in file order.</li>
	 * </ol>
	 * Similarity is reported as 1: these were asked for by name, not found by meaning.
	 *
	 * @param paths repo-relative file paths
	 * @param symbols class names ({@code Order}, {@code Order.Status}) or methods ({@code OrderService#refund})
	 */
	public List<RetrievedChunk> exactHits(UUID projectId, Collection<String> paths, Collection<String> symbols) {
		List<String> methods = symbols.stream().filter(symbol -> symbol.contains("#")).toList();
		List<String> methodPatterns = new ArrayList<>();
		methods.forEach(method -> methodPatterns.addAll(List.of(likeEscape(method) + "#%", likeEscape(method) + "(%")));
		List<String> allPatterns = new ArrayList<>(methodPatterns);
		symbols.stream().filter(symbol -> !symbol.contains("#")).forEach(type -> allPatterns.add(likeEscape(type) + "#%"));
		return jdbc.query(connection -> {
			var statement = connection.prepareStatement("""
					SELECT c.id, c.path, c.symbol, c.start_line, c.end_line, c.content, c.token_estimate,
					       1.0 AS similarity,
					       CASE WHEN c.symbol = ANY(?) OR c.symbol LIKE ANY(?) THEN 0
					            WHEN c.chunk_type = 'TYPE_HEADER' THEN 1
					            ELSE 2 END AS rank
					FROM code_chunk c
					WHERE c.project_id = ?
					  AND (c.path = ANY(?) OR c.symbol = ANY(?) OR c.symbol LIKE ANY(?))
					ORDER BY rank, c.path, c.start_line
					""");
			statement.setArray(1, connection.createArrayOf("text", methods.toArray()));
			statement.setArray(2, connection.createArrayOf("text", methodPatterns.toArray()));
			statement.setObject(3, projectId);
			statement.setArray(4, connection.createArrayOf("text", paths.toArray()));
			statement.setArray(5, connection.createArrayOf("text", symbols.toArray()));
			statement.setArray(6, connection.createArrayOf("text", allPatterns.toArray()));
			return statement;
		}, ChunkStore::retrievedChunk);
	}

	/** Every symbol in the project ({@code Order}, {@code Order#markRefunded}, ...), for checking a plan. */
	public List<String> symbols(UUID projectId) {
		return jdbc.queryForList("SELECT DISTINCT symbol FROM code_chunk WHERE project_id = ? AND symbol IS NOT NULL",
				String.class, projectId);
	}

	/** The symbols of the project's TYPE_HEADER chunks: every parsed type, nested ones as {@code Order.Status}. */
	public List<String> typeSymbols(UUID projectId) {
		return jdbc.queryForList("""
				SELECT DISTINCT symbol FROM code_chunk
				WHERE project_id = ? AND chunk_type = 'TYPE_HEADER'
				""", String.class, projectId);
	}

	private static RetrievedChunk retrievedChunk(ResultSet row, int rowNumber) throws SQLException {
		return new RetrievedChunk(row.getObject("id", UUID.class), row.getString("path"), row.getString("symbol"),
				row.getInt("start_line"), row.getInt("end_line"), row.getString("content"), row.getInt("token_estimate"),
				row.getDouble("similarity"));
	}

	/** {@code _} and {@code %} are wildcards in LIKE; a class name like {@code Order_Item} must match literally. */
	private static String likeEscape(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
