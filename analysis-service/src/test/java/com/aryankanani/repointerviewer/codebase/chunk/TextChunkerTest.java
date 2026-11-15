package com.aryankanani.repointerviewer.codebase.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Line numbers refer to the fixture files under src/test/resources/fixtures/sample-repo. */
class TextChunkerTest {

	private final TextChunker chunker = new TextChunker(120);

	@Test
	void yamlSplitsAtTopLevelSections() {
		List<Chunk> chunks = chunker.chunk(TestFiles.fixture("src/main/resources/application.yml"));

		assertThat(chunks).extracting(Chunk::chunkType, Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(ChunkType.CONFIG, 1, 10), tuple(ChunkType.CONFIG, 12, 16));
		assertThat(chunks.get(0).content()).startsWith("spring:").contains("password: <REDACTED:password>");
		assertThat(chunks.get(1).content()).startsWith("shop:");
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.symbol()).isNull());
	}

	@Test
	void flatYamlKeysWithValuesArePackedTogether() {
		List<Chunk> chunks = chunker.chunk(TestFiles.text("application.yml",
				"server.port: 8080\nspring.application.name: shop\n"));

		assertThat(chunks).extracting(Chunk::startLine, Chunk::endLine).containsExactly(tuple(1, 2));
	}

	@Test
	void markdownSplitsAtHeadings() {
		List<Chunk> chunks = chunker.chunk(TestFiles.fixture("README.md"));

		assertThat(chunks).extracting(Chunk::chunkType, Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(ChunkType.DOC, 1, 3), tuple(ChunkType.DOC, 5, 11), tuple(ChunkType.DOC, 13, 16));
	}

	@Test
	void hashLinesInsideACodeFenceAreNotHeadings() {
		String readme = "# Setup\n\n```bash\n# install the tools\nbrew install ollama\n```\n\n## Usage\nRun it.\n";

		List<Chunk> chunks = chunker.chunk(TestFiles.text("README.md", readme));

		assertThat(chunks).extracting(Chunk::startLine, Chunk::endLine).containsExactly(tuple(1, 6), tuple(8, 9));
	}

	@Test
	void sqlStatementsArePackedIntoOneChunk() {
		var file = TestFiles.fixture("src/main/resources/db/migration/V1__orders.sql");

		List<Chunk> chunks = chunker.chunk(file);

		assertThat(chunks).extracting(Chunk::chunkType, Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(ChunkType.SQL, 1, (int) file.content().lines().count()));
	}

	@Test
	void paragraphsArePackedUntilTheNextWouldPassTheLimit() {
		var file = TestFiles.text("notes.txt", "a\nb\nc\n\nd\ne\nf\n");   // blocks at lines 1-3 and 5-7

		assertThat(new TextChunker(7).chunk(file)).extracting(Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(1, 7));
		assertThat(new TextChunker(6).chunk(file)).extracting(Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(1, 3), tuple(5, 7));
	}

	@Test
	void blockLongerThanTheLimitIsCutIntoOverlappingWindows() {
		var file = TestFiles.text("notes.txt", "line\n".repeat(50));

		assertThat(new TextChunker(20).chunk(file)).extracting(Chunk::startLine, Chunk::endLine)
				.containsExactly(tuple(1, 20), tuple(11, 30), tuple(21, 40), tuple(31, 50));
	}

	@Test
	void blankFileHasNoChunks() {
		assertThat(chunker.chunk(TestFiles.text("notes.txt", ""))).isEmpty();
		assertThat(chunker.chunk(TestFiles.text("notes.txt", "\n   \n\n"))).isEmpty();
	}

	@ParameterizedTest(name = "{0} is {1}")
	@CsvSource({
			"src/main/resources/application.properties, CONFIG",
			"pom.xml,                                    TEXT",
			"Dockerfile,                                 TEXT",
			"src/main/resources/db/migration/V2__x.sql,  SQL",
			"docs/README.md,                             DOC" })
	void chunkTypeFollowsTheLanguage(String path, ChunkType type) {
		assertThat(chunker.chunk(TestFiles.text(path, "some content\n")))
				.extracting(Chunk::chunkType)
				.containsExactly(type);
	}

	@Test
	void chunkRejectsALineRangeTheDatabaseWouldReject() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Chunk("a.txt", ChunkType.TEXT, null, 0, 1, "x", 1));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Chunk("a.txt", ChunkType.TEXT, null, 5, 4, "x", 1));
	}

}
