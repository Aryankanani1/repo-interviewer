package com.aryankanani.repointerviewer.codebase;

import com.aryankanani.repointerviewer.codebase.store.ChunkStore;
import com.aryankanani.repointerviewer.codebase.store.CodeFile;
import com.aryankanani.repointerviewer.codebase.store.CodeFileRepository;
import com.aryankanani.repointerviewer.codebase.store.SnippetRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	/**
	 * The source text of each ref, in the same order: exactly lines {@code startLine..endLine} of the stored, redacted
	 * file, cut at its last line. One query per project. A file analyzed before V2 has no stored text: its refs fall
	 * back to {@link ChunkStore#snippets}, where lines that only a TYPE_HEADER covers show as a gap or give null.
	 */
	public List<String> snippets(List<SnippetRef> refs) {
		Map<UUID, Map<String, String>> texts = new HashMap<>();   // project id → path → file text
		refs.stream().collect(Collectors.groupingBy(SnippetRef::projectId,
				Collectors.mapping(SnippetRef::path, Collectors.toSet())))
				.forEach((projectId, paths) -> texts.put(projectId, codeFiles.findByProjectIdAndPathIn(projectId, paths)
						.stream().filter(file -> file.getContent() != null)
						.collect(Collectors.toMap(CodeFile::getPath, CodeFile::getContent))));
		List<String> snippets = new ArrayList<>(refs.size());
		List<Integer> withoutText = new ArrayList<>();
		for (int i = 0; i < refs.size(); i++) {
			SnippetRef ref = refs.get(i);
			String text = texts.get(ref.projectId()).get(ref.path());
			if (text == null) {
				withoutText.add(i);
			}
			snippets.add(text == null ? null : lines(text, ref.startLine(), ref.endLine()));
		}
		if (!withoutText.isEmpty()) {
			List<String> fromChunks = chunkStore.snippets(withoutText.stream().map(refs::get).toList());
			for (int i = 0; i < withoutText.size(); i++) {
				snippets.set(withoutText.get(i), fromChunks.get(i));
			}
		}
		return snippets;
	}

	/**
	 * Lines {@code startLine..endLine} (from 1), cut at the last line; null when the range starts after it. Split
	 * with {@link String#lines()} like {@code SourceLines}, so the numbers are the ones the chunks and refs use.
	 */
	private static String lines(String text, int startLine, int endLine) {
		List<String> lines = text.lines().toList();
		if (startLine > lines.size()) {
			return null;
		}
		return String.join("\n", lines.subList(startLine - 1, Math.min(endLine, lines.size())));
	}

	/** Every chunk symbol in the project: {@code Order}, {@code Order.Status}, {@code Order#markRefunded}, ... */
	public Set<String> symbols(UUID projectId) {
		return Set.copyOf(chunkStore.symbols(projectId));
	}

}
