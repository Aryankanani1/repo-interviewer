package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.common.error.RemoteModelUnavailableException;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisStatus;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkRangesRequest;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkSearchRequest;
import com.aryankanani.repointerviewer.contracts.analysis.CodeNames;
import com.aryankanani.repointerviewer.contracts.analysis.LineRange;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectContext;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectPage;
import com.aryankanani.repointerviewer.contracts.analysis.RetrievedChunk;
import com.aryankanani.repointerviewer.contracts.analysis.RetrievedContext;
import com.aryankanani.repointerviewer.contracts.analysis.SnippetRef;
import com.aryankanani.repointerviewer.contracts.analysis.SubmitProjectRequest;
import com.aryankanani.repointerviewer.contracts.analysis.TopicQuery;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link AnalysisApi} for the tests of a service that calls analysis-service (task 4.1): no other service, no HTTP.
 * <ul>
 * <li>Code: every project id has the fixture repository's code, from {@link FixtureAnalysis}, the snapshot of the
 * real analysis. So a second instance or a child process sees the same code without sharing anything.</li>
 * <li>Retrieval works like {@code Retriever}: exact hits on the targets first, ranked like {@code ChunkStore}; with
 * no vectors here, all the other chunks follow in file order instead of the nearest 8, then the token budget. The
 * fixture is small (all of it fits the budget), so any ref into its real code can be checked and an invented one
 * cannot. Line ranges and snippets are the real rules. analysis-service's {@code FakeAnalysisApiContractTest}
 * compares them, and its {@code RetrieverTest} the real nearest chunks.</li>
 * <li>Projects exist once added ({@link #readyProject()}, {@link #newProject()}) or submitted, until {@link #reset()}.</li>
 * </ul>
 * Failures are the exceptions the real HTTP client rebuilds ({@code DomainErrorHandler}).
 */
public class FakeAnalysisApi implements AnalysisApi {

	/** What analysis-service answers when Ollama is down (its {@code EmbeddingUnavailableException}). */
	public static final String EMBEDDING_UNAVAILABLE = "The code search model is not available right now. "
			+ "Try again in a minute.";

	private static final Instant FIRST_CREATED = Instant.parse("2026-01-01T00:00:00Z");

	private final FixtureAnalysis fixture = FixtureAnalysis.load();

	private final Map<String, FixtureAnalysis.File> files = fixture.files().stream()
			.collect(Collectors.toMap(FixtureAnalysis.File::path, file -> file));

	private final Map<UUID, ProjectDto> projects = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

	private final AtomicLong created = new AtomicLong();

	private final AtomicInteger searchesToFail = new AtomicInteger();

	private final AtomicReference<DomainException> nextFailure = new AtomicReference<>();

	private final AtomicReference<DomainException> everyFailure = new AtomicReference<>();

	// --- What a test sets up

	/**
	 * A READY project of {@link TestUsers#CANDIDATE} with the fixture's stats and summary, like
	 * {@code FixtureIndexer.readyProject()}.
	 */
	public UUID readyProject() {
		return add(AnalysisStatus.READY).id();
	}

	/** A READY project of another user (task 4.4). */
	public UUID readyProjectOf(String userId) {
		return add(AnalysisStatus.READY, userId).id();
	}

	/** A project still being analyzed (PARSING): no stats, summary or repo map yet. */
	public UUID newProject() {
		return add(AnalysisStatus.PARSING).id();
	}

	/** A project of the fixture in {@code status}, of {@link TestUsers#CANDIDATE}; each newer than the one before. */
	public ProjectDto add(AnalysisStatus status) {
		return add(status, TestUsers.CANDIDATE);
	}

	/** A project of the fixture in {@code status}, of {@code userId}. */
	public ProjectDto add(AnalysisStatus status, String userId) {
		UUID id = UUID.randomUUID();
		String repo = "shop-" + id.toString().substring(0, 8);
		boolean ready = status == AnalysisStatus.READY;
		return put(new ProjectDto(id, userId, "https://github.com/acme/" + repo, "acme", repo, "1".repeat(40),
				status, status == AnalysisStatus.FAILED ? "The analysis failed." : null, null, null, null,
				ready ? fixture.stats() : null, ready ? fixture.summary() : null, nextCreatedAt(), nextCreatedAt()));
	}

	public void reset() {
		projects.clear();
		calls.clear();
		searchesToFail.set(0);
		nextFailure.set(null);
		everyFailure.set(null);
	}

	/**
	 * The next {@code count} chunk searches fail like analysis-service with Ollama down: 503 LLM_UNAVAILABLE. Only a
	 * search embeds its query; line ranges, code names and snippets need no model and keep working.
	 */
	public void failNextSearches(int count) {
		searchesToFail.set(count);
	}

	/** The next call, of any method, throws {@code failure}. */
	public void failNextCall(DomainException failure) {
		nextFailure.set(failure);
	}

	/** Every call, of any method, throws {@code failure} until {@link #reset()}: analysis-service stays down. */
	public void failEveryCall(DomainException failure) {
		everyFailure.set(failure);
	}

	/** How many times {@code method} was called since the last {@link #reset()}: one call is one HTTP request. */
	public int calls(String method) {
		AtomicInteger count = calls.get(method);
		return count == null ? 0 : count.get();
	}

	// --- AnalysisApi

	@Override
	public ProjectDto submit(SubmitProjectRequest request) {
		called("submit");
		String[] parts = request.githubUrl().replaceAll("/+$", "").split("/");
		String owner = parts[parts.length - 2];
		String repo = parts[parts.length - 1];
		return put(new ProjectDto(UUID.randomUUID(), request.userId(), request.githubUrl(), owner, repo,
				"1".repeat(40), AnalysisStatus.SUBMITTED, null, request.goal(), request.domain(), request.targetRole(),
				null, null, nextCreatedAt(), nextCreatedAt()));
	}

	@Override
	public ProjectDto reanalyze(UUID id) {
		called("reanalyze");
		ProjectDto project = existing(id);
		if (project.status() != AnalysisStatus.FAILED) {
			return project;
		}
		return put(new ProjectDto(id, project.userId(), project.githubUrl(), project.owner(), project.repoName(),
				project.commitSha(), AnalysisStatus.SUBMITTED, null, project.goal(), project.domain(),
				project.targetRole(), null, null, project.createdAt(), nextCreatedAt()));
	}

	@Override
	public List<ProjectDto> findByIds(List<UUID> ids) {
		called("findByIds");
		return ids.stream().map(projects::get).filter(Objects::nonNull).toList();
	}

	@Override
	public ProjectPage page(String userId, int limit, Instant afterCreatedAt, UUID afterId) {
		called("page");
		List<ProjectDto> after = projects.values().stream()
				.filter(project -> project.userId().equals(userId))
				.filter(project -> afterId == null || isAfter(project, afterCreatedAt, afterId))
				.sorted(Comparator.comparing(ProjectDto::createdAt).thenComparing(ProjectDto::id).reversed())
				.toList();
		return new ProjectPage(after.stream().limit(limit).toList(), after.size() > limit);
	}

	/** After the position in newest-first order: older, or as old with a smaller id (the keyset rule). */
	private static boolean isAfter(ProjectDto project, Instant createdAt, UUID id) {
		int byTime = project.createdAt().compareTo(createdAt);
		return byTime < 0 || (byTime == 0 && project.id().compareTo(id) < 0);
	}

	@Override
	public ProjectContext context(UUID id) {
		called("context");
		ProjectDto project = existing(id);
		return new ProjectContext(id, project.status(), project.owner() + "/" + project.repoName(), project.goal(),
				project.targetRole(), project.summary(),
				project.status() == AnalysisStatus.READY ? fixture.repoMap() : null);
	}

	@Override
	public CodeNames codeNames(UUID id) {
		called("codeNames");
		return new CodeNames(files.keySet(), fixture.chunks().stream().map(FixtureAnalysis.Chunk::symbol)
				.filter(Objects::nonNull).collect(Collectors.toSet()));
	}

	@Override
	public RetrievedContext searchChunks(UUID id, ChunkSearchRequest request) {
		called("searchChunks");
		if (searchesToFail.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
			throw new RemoteModelUnavailableException(EMBEDDING_UNAVAILABLE);
		}
		TopicQuery query = request.query();
		Map<UUID, RetrievedChunk> merged = new LinkedHashMap<>();
		exactHits(query.targetPaths(), query.targetSymbols()).forEach(chunk -> merged.putIfAbsent(chunk.id(), chunk));
		fixture.chunks().stream()
				.filter(chunk -> !chunk.chunkType().equals("DOC"))
				.filter(chunk -> query.includeTests() || !"TEST".equals(files.get(chunk.path()).role()))
				.sorted(Comparator.comparing(FixtureAnalysis.Chunk::path).thenComparing(FixtureAnalysis.Chunk::startLine))
				.map(FakeAnalysisApi::retrieved)
				.forEach(chunk -> merged.putIfAbsent(chunk.id(), chunk));
		return withinBudget(merged.values(), request.maxTokens());
	}

	@Override
	public RetrievedContext chunksForRanges(UUID id, ChunkRangesRequest request) {
		called("chunksForRanges");
		Map<UUID, RetrievedChunk> merged = new LinkedHashMap<>();
		for (LineRange range : request.ranges()) {
			overlapping(range).forEach(chunk -> merged.putIfAbsent(chunk.id(), chunk));
		}
		return withinBudget(merged.values(), request.maxTokens());
	}

	@Override
	public List<String> snippets(List<SnippetRef> refs) {
		called("snippets");
		List<String> snippets = new ArrayList<>(refs.size());
		for (SnippetRef ref : refs) {
			FixtureAnalysis.File file = files.get(ref.path());
			snippets.add(file == null ? null : lines(file.content(), ref.startLine(), ref.endLine()));
		}
		return snippets;
	}

	// --- The rules of the real analysis-service

	/** {@code ChunkStore.exactHits}: by path, by symbol, or a method or type prefix; ranked, then file order. */
	private List<RetrievedChunk> exactHits(Collection<String> paths, Collection<String> symbols) {
		List<String> methods = symbols.stream().filter(symbol -> symbol.contains("#")).toList();
		List<String> methodPrefixes = methods.stream().flatMap(method -> Stream.of(method + "#", method + "(")).toList();
		List<String> allPrefixes = Stream.concat(methodPrefixes.stream(),
				symbols.stream().filter(symbol -> !symbol.contains("#")).map(type -> type + "#")).toList();
		return fixture.chunks().stream()
				.filter(chunk -> paths.contains(chunk.path()) || isAny(chunk.symbol(), symbols)
						|| startsWithAny(chunk.symbol(), allPrefixes))
				.sorted(Comparator.<FixtureAnalysis.Chunk>comparingInt(chunk -> isAny(chunk.symbol(), methods)
						|| startsWithAny(chunk.symbol(), methodPrefixes) ? 0
								: chunk.chunkType().equals("TYPE_HEADER") ? 1 : 2)
						.thenComparing(FixtureAnalysis.Chunk::path).thenComparing(FixtureAnalysis.Chunk::startLine))
				.map(FakeAnalysisApi::retrieved)
				.toList();
	}

	/** {@code ChunkStore.chunksOverlapping}: the chunks the range falls in; the TYPE_HEADER only when none does. */
	private List<RetrievedChunk> overlapping(LineRange range) {
		List<FixtureAnalysis.Chunk> inFile = fixture.chunks().stream()
				.filter(chunk -> chunk.path().equals(range.path()) && chunk.startLine() <= range.endLine()
						&& chunk.endLine() >= range.startLine())
				.sorted(Comparator.comparingInt(FixtureAnalysis.Chunk::startLine))
				.toList();
		List<FixtureAnalysis.Chunk> exact = inFile.stream().filter(chunk -> !chunk.chunkType().equals("TYPE_HEADER"))
				.toList();
		return (exact.isEmpty() ? inFile : exact).stream().map(FakeAnalysisApi::retrieved).toList();
	}

	/** {@code Retriever.withinBudget}: chunks while they fit; a big one is skipped; always at least one. */
	private static RetrievedContext withinBudget(Collection<RetrievedChunk> chunks, int maxTokens) {
		List<RetrievedChunk> selected = new ArrayList<>();
		int total = 0;
		for (RetrievedChunk chunk : chunks) {
			if (selected.isEmpty() || total + chunk.tokenEstimate() <= maxTokens) {
				selected.add(chunk);
				total += chunk.tokenEstimate();
			}
		}
		return new RetrievedContext(selected, total);
	}

	/** {@code CodebaseQueries.lines}: cut at the last line; null when the range starts after it. */
	private static String lines(String text, int startLine, int endLine) {
		List<String> lines = text.lines().toList();
		return startLine > lines.size() ? null
				: String.join("\n", lines.subList(startLine - 1, Math.min(endLine, lines.size())));
	}

	/** A text chunk has no symbol; {@code List.of(...).contains(null)} would throw. */
	private static boolean isAny(String symbol, Collection<String> symbols) {
		return symbol != null && symbols.contains(symbol);
	}

	private static boolean startsWithAny(String symbol, List<String> prefixes) {
		return symbol != null && prefixes.stream().anyMatch(symbol::startsWith);
	}

	/** Exact and range hits have similarity 1.0 in {@code ChunkStore}; a stable id per chunk. */
	private static RetrievedChunk retrieved(FixtureAnalysis.Chunk chunk) {
		UUID id = UUID.nameUUIDFromBytes((chunk.path() + ":" + chunk.startLine() + "-" + chunk.endLine() + ":"
				+ chunk.chunkType()).getBytes(StandardCharsets.UTF_8));
		return new RetrievedChunk(id, chunk.path(), chunk.symbol(), chunk.startLine(), chunk.endLine(),
				chunk.content(), chunk.tokenEstimate(), 1.0);
	}

	// --- Bookkeeping

	private ProjectDto existing(UUID id) {
		ProjectDto project = projects.get(id);
		if (project == null) {
			throw new DomainException(ErrorCode.NOT_FOUND, "Project not found.", "projectId");
		}
		return project;
	}

	private ProjectDto put(ProjectDto project) {
		projects.put(project.id(), project);
		return project;
	}

	private Instant nextCreatedAt() {
		return FIRST_CREATED.plusMillis(created.incrementAndGet());
	}

	private void called(String method) {
		calls.computeIfAbsent(method, name -> new AtomicInteger()).incrementAndGet();
		DomainException failure = nextFailure.getAndSet(null);
		if (failure == null) {
			failure = everyFailure.get();
		}
		if (failure != null) {
			throw failure;
		}
	}

}
