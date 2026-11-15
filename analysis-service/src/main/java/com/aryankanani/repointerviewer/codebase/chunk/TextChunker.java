package com.aryankanani.repointerviewer.codebase.chunk;

import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.common.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Chunks YAML, properties, SQL, Markdown and other text files, and Java that does not parse (LLD §6.6).
 *
 * <ul>
 * <li>A block is a run of non-blank lines. Blank lines are allowed cut points: blocks are packed into one chunk
 * until the next block would pass {@code app.chunking.max-chunk-lines}. Without packing, a README or a SQL file
 * would become dozens of one-line chunks, which embed poorly.</li>
 * <li>Section starts are forced cut points: a YAML top-level key that opens a section ({@code spring:}), a YAML
 * document separator ({@code ---}), a Markdown heading outside a code fence.</li>
 * <li>A single block longer than the limit is cut into overlapping windows ({@link LineWindows}).</li>
 * </ul>
 * Chunks hold exactly their lines (see {@link Chunk}) and have no symbol.
 */
@Component
public class TextChunker {

	/** {@code spring:} or {@code spring:  # comment} at column 0, not {@code server.port: 8080}; or {@code ---}. */
	private static final Pattern YAML_SECTION = Pattern.compile("[^\\s#\\-][^#]*:\\s*(#.*)?|---\\s*");

	private static final Pattern MARKDOWN_HEADING = Pattern.compile("#{1,6}\\s.*");

	private static final Pattern MARKDOWN_FENCE = Pattern.compile(" {0,3}(```|~~~).*");

	private final int maxChunkLines;

	@Autowired
	TextChunker(AppProperties properties) {
		this(properties.chunking().maxChunkLines());
	}

	TextChunker(int maxChunkLines) {
		this.maxChunkLines = maxChunkLines;
	}

	public List<Chunk> chunk(RedactedFile file) {
		SourceLines lines = new SourceLines(file.content());
		boolean[] sectionStarts = sectionStarts(file.language(), lines);
		ChunkType type = typeOf(file.language());
		List<Chunk> chunks = new ArrayList<>();

		int chunkStart = 0;   // 0: no chunk open
		int chunkEnd = 0;
		int line = 1;
		while (line <= lines.count()) {
			if (lines.isBlank(line)) {
				line++;
				continue;
			}
			int blockStart = line;
			int blockEnd = line;
			while (blockEnd < lines.count() && !lines.isBlank(blockEnd + 1) && !sectionStarts[blockEnd + 1]) {
				blockEnd++;
			}
			if (chunkStart > 0 && (sectionStarts[blockStart] || blockEnd - chunkStart + 1 > maxChunkLines)) {
				addWindows(chunks, file.path(), type, lines, chunkStart, chunkEnd);
				chunkStart = 0;
			}
			if (chunkStart == 0) {
				chunkStart = blockStart;
			}
			chunkEnd = blockEnd;
			line = blockEnd + 1;
		}
		if (chunkStart > 0) {
			addWindows(chunks, file.path(), type, lines, chunkStart, chunkEnd);
		}
		return chunks;
	}

	private void addWindows(List<Chunk> chunks, String path, ChunkType type, SourceLines lines, int start, int end) {
		for (LineWindows.Range window : LineWindows.split(start, end, maxChunkLines)) {
			chunks.add(Chunk.of(path, type, null, window.start(), window.end(), lines.slice(window.start(), window.end())));
		}
	}

	/** {@code starts[n]} is true when line {@code n} must begin a new chunk. Index 0 is unused. */
	private static boolean[] sectionStarts(String language, SourceLines lines) {
		boolean[] starts = new boolean[lines.count() + 1];
		boolean inFence = false;
		for (int n = 1; n <= lines.count(); n++) {
			String text = lines.line(n);
			if (language.equals("yaml")) {
				starts[n] = YAML_SECTION.matcher(text).matches();
			}
			else if (language.equals("markdown")) {
				if (MARKDOWN_FENCE.matcher(text).matches()) {
					inFence = !inFence;   // "# install" inside a ```bash block is a comment, not a heading
				}
				else {
					starts[n] = !inFence && MARKDOWN_HEADING.matcher(text).matches();
				}
			}
		}
		return starts;
	}

	private static ChunkType typeOf(String language) {
		return switch (language) {
			case "yaml", "properties" -> ChunkType.CONFIG;
			case "sql" -> ChunkType.SQL;
			case "markdown" -> ChunkType.DOC;
			default -> ChunkType.TEXT;
		};
	}

}
