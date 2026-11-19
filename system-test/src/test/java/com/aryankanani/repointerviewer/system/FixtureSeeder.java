package com.aryankanani.repointerviewer.system;

import com.aryankanani.repointerviewer.FakeModelServer;
import com.aryankanani.repointerviewer.FixtureAnalysis;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the fixture repository's analysis into analysis-service's schema, as {@code analysis_svc} (task 4.1 step
 * 4b): the READY project, its files and its chunks, from the checked snapshot ({@link FixtureAnalysis}, which
 * analysis-service's {@code FixtureSnapshotTest} keeps equal to what its real indexer stores). analysis-service only
 * clones from github.com, by design, so a test without the network cannot submit the repository; everything after
 * the analysis is the real thing. Every chunk gets {@link FakeModelServer#VECTOR}, the vector the fake Ollama gives a
 * search query too: code is found by its exact paths and symbols first.
 */
final class FixtureSeeder {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private FixtureSeeder() {
	}

	/** @return the READY project's id */
	static UUID seed(String jdbcUrl) throws SQLException {
		FixtureAnalysis fixture = FixtureAnalysis.load();
		try (Connection db = DriverManager.getConnection(jdbcUrl, "analysis_svc", "analysis_svc")) {
			db.setAutoCommit(false);
			UUID project = insertProject(db, fixture);
			Map<String, UUID> files = new HashMap<>();
			for (FixtureAnalysis.File file : fixture.files()) {
				files.put(file.path(), insertFile(db, project, file));
			}
			String vector = vectorLiteral();
			try (PreparedStatement chunk = db.prepareStatement("""
					INSERT INTO code_chunk (project_id, file_id, path, chunk_type, symbol, start_line, end_line,
					    content, token_estimate, embedding)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
					""")) {
				for (FixtureAnalysis.Chunk c : fixture.chunks()) {
					chunk.setObject(1, project);
					chunk.setObject(2, files.get(c.path()));
					chunk.setString(3, c.path());
					chunk.setString(4, c.chunkType());
					chunk.setString(5, c.symbol());
					chunk.setInt(6, c.startLine());
					chunk.setInt(7, c.endLine());
					chunk.setString(8, c.content());
					chunk.setInt(9, c.tokenEstimate());
					chunk.setString(10, vector);
					chunk.addBatch();
				}
				chunk.executeBatch();
			}
			db.commit();
			return project;
		}
	}

	private static UUID insertProject(Connection db, FixtureAnalysis fixture) throws SQLException {
		ProjectDto.Stats stats = fixture.stats();
		try (PreparedStatement insert = db.prepareStatement("""
				INSERT INTO project (user_id, github_url, owner, repo_name, commit_sha, status, file_count,
				    java_file_count, total_lines, chunk_count, primary_language, summary, repo_map,
				    analysis_started_at, analysis_completed_at)
				VALUES (?, 'https://github.com/acme/shop', 'acme', 'shop', ?, 'READY', ?, ?, ?, ?, ?, CAST(? AS jsonb),
				    ?, now(), now())
				RETURNING id
				""")) {
			insert.setString(1, TestUsers.CANDIDATE);   // task 4.4: the user the system test's token names
			insert.setString(2, "1".repeat(40));
			insert.setInt(3, stats.fileCount());
			insert.setInt(4, stats.javaFileCount());
			insert.setInt(5, stats.totalLines());
			insert.setInt(6, stats.chunkCount());
			insert.setString(7, stats.primaryLanguage());
			insert.setString(8, JSON.writeValueAsString(fixture.summary()));
			insert.setString(9, fixture.repoMap());
			return returnedId(insert);
		}
	}

	private static UUID insertFile(Connection db, UUID project, FixtureAnalysis.File file) throws SQLException {
		try (PreparedStatement insert = db.prepareStatement("""
				INSERT INTO code_file (project_id, path, language, role, line_count, size_bytes, content_sha, content)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""")) {
			byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
			insert.setObject(1, project);
			insert.setString(2, file.path());
			insert.setString(3, language(file.path()));
			insert.setString(4, file.role());
			insert.setInt(5, (int) file.content().lines().count());
			insert.setInt(6, bytes.length);
			insert.setString(7, sha256(bytes));
			insert.setString(8, file.content());
			return returnedId(insert);
		}
	}

	private static UUID returnedId(PreparedStatement insert) throws SQLException {
		try (ResultSet row = insert.executeQuery()) {
			row.next();
			return row.getObject(1, UUID.class);
		}
	}

	private static String language(String path) {
		String extension = path.substring(path.lastIndexOf('.') + 1);
		return switch (extension) {
			case "java" -> "java";
			case "yml", "yaml" -> "yaml";
			case "xml" -> "xml";
			case "sql" -> "sql";
			case "md" -> "markdown";
			default -> "text";
		};
	}

	private static String vectorLiteral() {
		StringBuilder literal = new StringBuilder("[");
		for (float value : FakeModelServer.VECTOR) {
			literal.append(literal.length() > 1 ? "," : "").append(value);
		}
		return literal.append(']').toString();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
