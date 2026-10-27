package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import com.aryankanani.repointerviewer.codebase.chunk.JavaChunker;
import com.aryankanani.repointerviewer.codebase.chunk.TextChunker;
import com.aryankanani.repointerviewer.codebase.scan.FileClassifier;
import com.aryankanani.repointerviewer.codebase.scan.FileScanner;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.codebase.scan.ScannedFile;
import com.aryankanani.repointerviewer.codebase.scan.SecretRedactor;
import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.CodeFile;
import com.aryankanani.repointerviewer.codebase.store.CodeFileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts the fixture repository into the database for one project, the way the PARSING stage will (task 1.9):
 * scan, classify, redact, chunk, then save the files and their chunks (no embeddings yet). Registered as a
 * bean by {@link IntegrationTest}.
 */
public class FixtureIndexer {

	private final JdbcTemplate jdbc;

	private final FileScanner scanner;

	private final FileClassifier classifier;

	private final SecretRedactor redactor;

	private final JavaChunker javaChunker;

	private final TextChunker textChunker;

	private final CodeFileRepository files;

	private final ChunkStore chunkStore;

	FixtureIndexer(JdbcTemplate jdbc, FileScanner scanner, FileClassifier classifier, SecretRedactor redactor,
			JavaChunker javaChunker, TextChunker textChunker, CodeFileRepository files, ChunkStore chunkStore) {
		this.jdbc = jdbc;
		this.scanner = scanner;
		this.classifier = classifier;
		this.redactor = redactor;
		this.javaChunker = javaChunker;
		this.textChunker = textChunker;
		this.files = files;
		this.chunkStore = chunkStore;
	}

	/** A new project row. The random repository name keeps the unique (github_url, commit_sha) check happy. */
	public UUID newProject() {
		String repo = "shop-" + UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO project (github_url, owner, repo_name, commit_sha, status)
				VALUES (?, 'acme', ?, ?, 'PARSING')
				RETURNING id
				""", UUID.class, "https://github.com/acme/" + repo, repo, LocalDirectoryRepoFetcher.DEFAULT_SHA);
	}

	/** @return every chunk saved for the project */
	public List<Chunk> index(UUID projectId) {
		List<Chunk> saved = new ArrayList<>();
		for (ScannedFile file : scanner.scan(LocalDirectoryRepoFetcher.fixtureRoot()).files()) {
			CodeFile codeFile = files.save(new CodeFile(projectId, file, classifier.classify(file)));
			RedactedFile redacted = redactor.redact(file);
			List<Chunk> chunks = file.language().equals("java") ? javaChunker.chunk(redacted)
					: textChunker.chunk(redacted);
			chunkStore.insert(projectId, codeFile.getId(), chunks);
			saved.addAll(chunks);
		}
		return saved;
	}

}
