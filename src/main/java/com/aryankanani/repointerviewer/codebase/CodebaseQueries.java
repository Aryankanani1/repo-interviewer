package com.aryankanani.repointerviewer.codebase;

import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.CodeFileRepository;
import com.aryankanani.repointerviewer.codebase.store.SnippetRef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only questions other modules ask about an indexed repository. Answers are plain values, never entities. */
@Component
public class CodebaseQueries {

	private final ChunkStore chunkStore;

	private final CodeFileRepository codeFiles;

	CodebaseQueries(ChunkStore chunkStore, CodeFileRepository codeFiles) {
		this.chunkStore = chunkStore;
		this.codeFiles = codeFiles;
	}

	/**
	 * Every name a model may use to point at code in this repository: type names as parsed ({@code Order},
	 * {@code Order.Status}, and the nested {@code Status} on its own), file names ({@code OrderService.java},
	 * {@code application.yml}), and Java file names without {@code .java}, which also covers files that do not
	 * parse. Case-sensitive.
	 */
	@Transactional(readOnly = true)
	public Set<String> knownNames(UUID projectId) {
		Set<String> names = new HashSet<>();
		for (String symbol : chunkStore.typeSymbols(projectId)) {
			names.add(symbol);
			names.add(symbol.substring(symbol.lastIndexOf('.') + 1));
		}
		for (String path : codeFiles.findPathsByProjectId(projectId)) {
			String fileName = path.substring(path.lastIndexOf('/') + 1);
			names.add(fileName);
			if (fileName.endsWith(".java")) {
				names.add(fileName.substring(0, fileName.length() - ".java".length()));
			}
		}
		return names;
	}

	/** Every file path in the project, repo-relative. */
	@Transactional(readOnly = true)
	public Set<String> paths(UUID projectId) {
		return Set.copyOf(codeFiles.findPathsByProjectId(projectId));
	}

	/** The source text of each ref, in the same order, in one query ({@link ChunkStore#snippets}). */
	public List<String> snippets(List<SnippetRef> refs) {
		return chunkStore.snippets(refs);
	}

	/** Every chunk symbol in the project: {@code Order}, {@code Order.Status}, {@code Order#markRefunded}, ... */
	public Set<String> symbols(UUID projectId) {
		return Set.copyOf(chunkStore.symbols(projectId));
	}

}
