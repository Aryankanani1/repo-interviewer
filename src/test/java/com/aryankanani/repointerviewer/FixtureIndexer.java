package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.codebase.CodebaseIndexer;
import com.aryankanani.repointerviewer.codebase.IndexResult;
import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import com.aryankanani.repointerviewer.codebase.chunk.JavaChunker;
import com.aryankanani.repointerviewer.codebase.chunk.TextChunker;
import com.aryankanani.repointerviewer.codebase.embed.EmbeddingService;
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
 * Puts the fixture repository into the database for one project: {@link #index} the way the PARSING stage does
 * (files and chunks, no embeddings), or {@link #readyProject} for a fully analyzed READY project. Registered as a
 * bean by {@link IntegrationTest}.
 */
public class FixtureIndexer {

	/** A summary of the fixture, as the SUMMARIZING stage would store it. */
	public static final String SUMMARY_JSON = """
			{"oneLiner": "A small order service that places and refunds orders.",
			 "architectureStyle": "layered monolith",
			 "techStack": ["Spring Boot", "Spring Data JPA", "PostgreSQL", "Flyway"],
			 "modules": [{"name": "order", "responsibility": "Orders and refunds",
			              "keyClasses": ["OrderService", "OrderController", "Order"]}],
			 "notablePatterns": ["Refund limit checked in OrderService#refund"],
			 "riskAreas": ["Concurrent refunds on the same order in OrderService#refund"]}
			""";

	private final JdbcTemplate jdbc;

	private final FileScanner scanner;

	private final FileClassifier classifier;

	private final SecretRedactor redactor;

	private final JavaChunker javaChunker;

	private final TextChunker textChunker;

	private final CodeFileRepository files;

	private final ChunkStore chunkStore;

	private final CodebaseIndexer codebaseIndexer;

	private final EmbeddingService embeddingService;

	FixtureIndexer(JdbcTemplate jdbc, FileScanner scanner, FileClassifier classifier, SecretRedactor redactor,
			JavaChunker javaChunker, TextChunker textChunker, CodeFileRepository files, ChunkStore chunkStore,
			CodebaseIndexer codebaseIndexer, EmbeddingService embeddingService) {
		this.jdbc = jdbc;
		this.scanner = scanner;
		this.classifier = classifier;
		this.redactor = redactor;
		this.javaChunker = javaChunker;
		this.textChunker = textChunker;
		this.files = files;
		this.chunkStore = chunkStore;
		this.codebaseIndexer = codebaseIndexer;
		this.embeddingService = embeddingService;
	}

	/**
	 * A READY project of the fixture: indexed with the production {@link CodebaseIndexer}, embedded with the fake
	 * model, with stats, repo map and {@link #SUMMARY_JSON}.
	 */
	public UUID readyProject() {
		UUID project = newProject();
		IndexResult index = codebaseIndexer.index(project, LocalDirectoryRepoFetcher.fixtureRoot());
		embeddingService.embedPending(project);
		jdbc.update("""
				UPDATE project SET status = 'READY', repo_map = ?, summary = CAST(? AS jsonb), file_count = ?,
				    java_file_count = ?, total_lines = ?, chunk_count = ?, primary_language = ?
				WHERE id = ?
				""", index.repoMap(), SUMMARY_JSON, index.fileCount(), index.javaFileCount(), index.totalLines(),
				index.chunkCount(), index.primaryLanguage(), project);
		return project;
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
