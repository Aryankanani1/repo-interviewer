package com.aryankanani.repointerviewer.codebase;

import com.aryankanani.repointerviewer.codebase.chunk.Chunk;
import com.aryankanani.repointerviewer.codebase.chunk.JavaChunker;
import com.aryankanani.repointerviewer.codebase.chunk.TextChunker;
import com.aryankanani.repointerviewer.codebase.map.RepoFile;
import com.aryankanani.repointerviewer.codebase.map.RepoMapBuilder;
import com.aryankanani.repointerviewer.codebase.scan.FileClassifier;
import com.aryankanani.repointerviewer.codebase.scan.FileRole;
import com.aryankanani.repointerviewer.codebase.scan.FileScanner;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.codebase.scan.ScannedFile;
import com.aryankanani.repointerviewer.codebase.scan.SecretRedactor;
import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.CodeFile;
import com.aryankanani.repointerviewer.codebase.store.CodeFileRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The PARSING stage (LLD §6.3), the {@code codebase} module's entry point for the analysis job: scan, classify,
 * redact, chunk, save, and build the repo map. The job lives in {@code project}, which may not touch the
 * {@code CodeFile} entity (ArchitectureTest), so it gets plain numbers and text back ({@link IndexResult}).
 *
 * <p>All reading and chunking happens in memory first; the writes then go in one short transaction. That
 * transaction first deletes what an earlier analysis of the same project saved ({@code code_chunk} goes with it,
 * {@code ON DELETE CASCADE}), so a rerun after {@code reanalyzeProject} starts clean. Embeddings are left NULL for
 * the EMBEDDING stage.
 */
@Component
public class CodebaseIndexer {

	/** Data and documentation formats: a repository is not "written in" these. */
	private static final Set<String> NOT_PROGRAMMING_LANGUAGES = Set.of("markdown", "yaml", "properties", "xml",
			"sql", "dockerfile", "text");

	/** One file, read and chunked, before anything is saved. */
	private record IndexedFile(ScannedFile scanned, FileRole role, RedactedFile redacted, List<Chunk> chunks) {
	}

	private final FileScanner scanner;

	private final FileClassifier classifier;

	private final SecretRedactor redactor;

	private final JavaChunker javaChunker;

	private final TextChunker textChunker;

	private final CodeFileRepository codeFiles;

	private final ChunkStore chunkStore;

	private final RepoMapBuilder repoMapBuilder;

	private final TransactionTemplate transaction;

	CodebaseIndexer(FileScanner scanner, FileClassifier classifier, SecretRedactor redactor, JavaChunker javaChunker,
			TextChunker textChunker, CodeFileRepository codeFiles, ChunkStore chunkStore, RepoMapBuilder repoMapBuilder,
			PlatformTransactionManager transactionManager) {
		this.scanner = scanner;
		this.classifier = classifier;
		this.redactor = redactor;
		this.javaChunker = javaChunker;
		this.textChunker = textChunker;
		this.codeFiles = codeFiles;
		this.chunkStore = chunkStore;
		this.repoMapBuilder = repoMapBuilder;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * @param repoDir the cloned repository; only read, never executed
	 * @throws com.aryankanani.repointerviewer.common.error.DomainException {@code REPO_TOO_LARGE} or
	 *         {@code UNSUPPORTED_LANGUAGE} from the scanner
	 */
	public IndexResult index(UUID projectId, Path repoDir) {
		List<IndexedFile> files = new ArrayList<>();
		for (ScannedFile file : scanner.scan(repoDir).files()) {
			RedactedFile redacted = redactor.redact(file);
			List<Chunk> chunks = file.language().equals("java") ? javaChunker.chunk(redacted)
					: textChunker.chunk(redacted);
			files.add(new IndexedFile(file, classifier.classify(file), redacted, chunks));
		}

		transaction.executeWithoutResult(status -> save(projectId, files));

		List<RedactedFile> redacted = files.stream().map(IndexedFile::redacted).toList();
		return new IndexResult(
				files.size(),
				(int) files.stream().filter(file -> file.scanned().language().equals("java")).count(),
				files.stream().mapToInt(file -> file.scanned().lineCount()).sum(),
				files.stream().mapToInt(file -> file.chunks().size()).sum(),
				primaryLanguage(files),
				repoMapBuilder.build(files.stream().map(file -> new RepoFile(file.redacted(), file.role())).toList()),
				SummarySources.buildFiles(redacted),
				SummarySources.readme(redacted));
	}

	private void save(UUID projectId, List<IndexedFile> files) {
		codeFiles.deleteAllByProjectId(projectId);
		// JPA and JDBC in one transaction: Hibernate holds its INSERTs until it flushes (the ids are made in Java,
		// so it has no reason to write early). The chunk rows below are plain JDBC and point at code_file, so the
		// files must reach the database first, or the foreign key fails.
		List<CodeFile> saved = codeFiles.saveAllAndFlush(
				files.stream().map(file -> new CodeFile(projectId, file.scanned(), file.role())).toList());
		for (int i = 0; i < files.size(); i++) {
			chunkStore.insert(projectId, saved.get(i).getId(), files.get(i).chunks());
		}
	}

	/** The programming language with the most lines, ignoring build scripts; null if there is none. */
	private static String primaryLanguage(List<IndexedFile> files) {
		Map<String, Integer> lines = files.stream()
				.filter(file -> file.role() != FileRole.BUILD)
				.filter(file -> !NOT_PROGRAMMING_LANGUAGES.contains(file.scanned().language()))
				.collect(Collectors.groupingBy(file -> file.scanned().language(),
						Collectors.summingInt(file -> file.scanned().lineCount())));
		return lines.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

}
