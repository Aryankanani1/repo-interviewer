package com.aryankanani.repointerviewer.contracts.analysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * What the rest of the system may ask the analysis side (task 4.1, LLD §2): projects, their context for prompts, and
 * their code. One interface for both ends: the analysis side's {@code @RestController} implements it (Spring MVC
 * maps the {@code @HttpExchange} annotations like {@code @RequestMapping}), and a caller gets an HTTP client for it
 * from Spring, so the paths and types of the two ends cannot drift apart.
 *
 * <p>Expected failures are a {@code DomainException} on the server and an RFC 9457 problem detail on the wire, with
 * the {@code ErrorCode} in {@code code} and the input field in {@code field}: 404 for NOT_FOUND, 429 for
 * BUDGET_EXCEEDED, 503 for LLM_UNAVAILABLE, 422 for the others.
 */
@HttpExchange("/internal")
public interface AnalysisApi {

	/** Checks the URL and the repository, then starts the analysis (or returns the project already analyzed). */
	@PostExchange("/projects")
	ProjectDto submit(@RequestBody SubmitProjectRequest request);

	/** Starts a FAILED analysis again; any other status returns the project unchanged. NOT_FOUND if unknown. */
	@PostExchange("/projects/{id}/reanalyze")
	ProjectDto reanalyze(@PathVariable UUID id);

	/** The projects that exist among {@code ids}, in no particular order: an unknown id is left out. */
	@GetExchange("/projects")
	List<ProjectDto> findByIds(@RequestParam List<UUID> ids);

	/**
	 * Newest first, keyset paging: the next page starts after the last project of this one, given by its
	 * {@code createdAt} and {@code id} (both or neither).
	 */
	@GetExchange("/projects/page")
	ProjectPage page(@RequestParam int limit, @RequestParam(required = false) Instant afterCreatedAt,
			@RequestParam(required = false) UUID afterId);

	/** What prompts need about a project: status, the candidate's context, summary and repo map. NOT_FOUND if unknown. */
	@GetExchange("/projects/{id}/context")
	ProjectContext context(@PathVariable UUID id);

	/** Every file path and chunk symbol of the project, to check what the model cites. */
	@GetExchange("/projects/{id}/code-names")
	CodeNames codeNames(@PathVariable UUID id);

	/** The chunks for a topic: exact hits on its targets first, then the nearest by meaning, within the budget. */
	@PostExchange("/projects/{id}/chunks/search")
	RetrievedContext searchChunks(@PathVariable UUID id, @RequestBody ChunkSearchRequest request);

	/** The chunks that overlap the given line ranges, within the budget. */
	@PostExchange("/projects/{id}/chunks/ranges")
	RetrievedContext chunksForRanges(@PathVariable UUID id, @RequestBody ChunkRangesRequest request);

	/** The code of each ref, in the same order; null where the file or the lines no longer exist. */
	@PostExchange("/snippets")
	List<String> snippets(@RequestBody List<SnippetRef> refs);

}
