package com.aryankanani.repointerviewer.codebase.store;

import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
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

	/** Shown in a snippet for lines no exact chunk holds ({@link #snippets}). */
	static final String GAP = "…";

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

	/**
	 * The chunks a line range of one file falls in, in file order: the code a question's ref points at. Method,
	 * config and other chunks hold exactly their lines, so they are preferred; only when none overlaps (for
	 * example a ref on a field) is the type's condensed TYPE_HEADER returned instead.
	 */
	public List<RetrievedChunk> chunksOverlapping(UUID projectId, String path, int startLine, int endLine) {
		String sql = """
				SELECT id, path, symbol, start_line, end_line, content, token_estimate, 1.0 AS similarity
				FROM code_chunk
				WHERE project_id = ? AND path = ? AND start_line <= ? AND end_line >= ? AND chunk_type %s 'TYPE_HEADER'
				ORDER BY start_line
				""";
		List<RetrievedChunk> exact = jdbc.query(sql.formatted("<>"), ChunkStore::retrievedChunk, projectId, path, endLine,
				startLine);
		return exact.isEmpty()
				? jdbc.query(sql.formatted("="), ChunkStore::retrievedChunk, projectId, path, endLine, startLine)
				: exact;
	}

	/**
	 * The source text of many line ranges in <b>one</b> query (task 1.13: {@code CodeRef.snippet} for every ref of a
	 * GraphQL response at once). The ranges go in as four parallel arrays that {@code unnest(...) WITH ORDINALITY}
	 * turns into rows (one per range, numbered), joined to the chunks they overlap.
	 *
	 * <p>Only chunks that hold exactly their lines are used (all but TYPE_HEADER, see {@code Chunk}), so each line
	 * comes back as written. Lines no such chunk holds (a class's fields live only in its condensed header) are
	 * shown as one {@value #GAP} line per gap.
	 *
	 * @return one entry per ref, in the same order; null when none of its lines is stored
	 */
	public List<String> snippets(List<SnippetRef> refs) {
		if (refs.isEmpty()) {
			return List.of();
		}
		List<TreeMap<Integer, String>> lines = new ArrayList<>();
		refs.forEach(ref -> lines.add(new TreeMap<>()));
		jdbc.query(connection -> {
			var statement = connection.prepareStatement("""
					SELECT r.idx, c.start_line, c.content
					FROM unnest(?::uuid[], ?::text[], ?::int[], ?::int[])
					     WITH ORDINALITY AS r(project_id, path, start_line, end_line, idx)
					JOIN code_chunk c ON c.project_id = r.project_id AND c.path = r.path
					     AND c.start_line <= r.end_line AND c.end_line >= r.start_line AND c.chunk_type <> 'TYPE_HEADER'
					""");
			statement.setArray(1, connection.createArrayOf("uuid", refs.stream().map(SnippetRef::projectId).toArray()));
			statement.setArray(2, connection.createArrayOf("text", refs.stream().map(SnippetRef::path).toArray()));
			statement.setArray(3, connection.createArrayOf("integer", refs.stream().map(SnippetRef::startLine).toArray()));
			statement.setArray(4, connection.createArrayOf("integer", refs.stream().map(SnippetRef::endLine).toArray()));
			return statement;
		}, row -> {
			int index = (int) row.getLong("idx") - 1;   // ORDINALITY counts from 1
			SnippetRef ref = refs.get(index);
			String[] content = row.getString("content").split("\n", -1);
			for (int i = 0; i < content.length; i++) {
				int line = row.getInt("start_line") + i;
				if (line >= ref.startLine() && line <= ref.endLine()) {
					lines.get(index).put(line, content[i]);
				}
			}
		});
		List<String> snippets = new ArrayList<>(refs.size());
		for (int i = 0; i < refs.size(); i++) {
			snippets.add(render(refs.get(i), lines.get(i)));
		}
		return snippets;
	}

	private static String render(SnippetRef ref, TreeMap<Integer, String> lines) {
		if (lines.isEmpty()) {
			return null;
		}
		List<String> out = new ArrayList<>();
		boolean inGap = false;
		for (int line = ref.startLine(); line <= ref.endLine(); line++) {
			String text = lines.get(line);
			if (text != null) {
				out.add(text);
				inGap = false;
			}
			else if (!inGap) {
				out.add(GAP);
				inGap = true;
			}
		}
		return String.join("\n", out);
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
