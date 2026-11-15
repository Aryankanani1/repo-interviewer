package com.aryankanani.repointerviewer.codebase.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.LocalDirectoryRepoFetcher;
import com.aryankanani.repointerviewer.codebase.scan.RedactedFile;
import com.aryankanani.repointerviewer.common.TokenEstimator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/** The line-range rule from {@link Chunk}, checked on every chunk of every fixture file. */
class FixtureChunksTest {

	static final List<String> FIXTURE_FILES = LocalDirectoryRepoFetcher.FIXTURE_FILES;

	private final TextChunker textChunker = new TextChunker(120);

	private final JavaChunker javaChunker = new JavaChunker(120, 4, textChunker);

	@ParameterizedTest
	@FieldSource("FIXTURE_FILES")
	void everyChunkExceptTypeHeadersHoldsExactlyItsLines(String path) {
		RedactedFile file = TestFiles.fixture(path);
		List<String> lines = file.content().lines().toList();

		List<Chunk> chunks = path.endsWith(".java") ? javaChunker.chunk(file) : textChunker.chunk(file);

		assertThat(chunks).isNotEmpty();
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.path()).isEqualTo(path);
			assertThat(chunk.endLine()).isLessThanOrEqualTo(lines.size());
			assertThat(chunk.tokenEstimate()).isEqualTo(TokenEstimator.estimate(chunk.content()));
			if (chunk.chunkType() != ChunkType.TYPE_HEADER) {
				assertThat(chunk.content()).as(chunk.toString())
						.isEqualTo(String.join("\n", lines.subList(chunk.startLine() - 1, chunk.endLine())));
			}
		});
	}

}
