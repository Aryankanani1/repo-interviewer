package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.RepoSummary;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What analysis-service stores for the fixture repository ({@code fixtures/sample-repo} in its tests): the redacted
 * files, the chunks, the repo map, the stats and the summary (task 4.1). analysis-service's
 * {@code FixtureSnapshotTest} writes it from the real indexer to {@value #RESOURCE} and fails when the file is out of
 * date; {@link FakeAnalysisApi} reads it, so the other services' tests get the code the real analysis would give them.
 */
public record FixtureAnalysis(String repoMap, RepoSummary summary, ProjectDto.Stats stats, List<File> files,
		List<Chunk> chunks) {

	public static final String RESOURCE = "fixtures/sample-repo-analysis.json";

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	/** A {@code code_file} row: {@code role} is a {@code FileRole} name, {@code content} the redacted text. */
	public record File(String path, String role, String content) {
	}

	/** A {@code code_chunk} row, without its embedding. {@code chunkType} is a {@code ChunkType} name. */
	public record Chunk(String path, String symbol, String chunkType, int startLine, int endLine, String content,
			int tokenEstimate) {
	}

	public static FixtureAnalysis load() {
		try (InputStream in = FixtureAnalysis.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing: run analysis-service's FixtureSnapshotTest "
						+ "with -Dfixture.snapshot.update=true");
			}
			return JSON.readValue(in, FixtureAnalysis.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** The snapshot file's exact text: fields in declaration order, indented, one newline at the end. */
	public String toJson() {
		return JSON.writeValueAsString(this) + "\n";
	}

}
