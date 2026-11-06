package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.codebase.CodebaseQueries;
import com.aryankanani.repointerviewer.codebase.retrieve.Retriever;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkRangesRequest;
import com.aryankanani.repointerviewer.contracts.analysis.ChunkSearchRequest;
import com.aryankanani.repointerviewer.contracts.analysis.CodeNames;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectContext;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectPage;
import com.aryankanani.repointerviewer.contracts.analysis.RetrievedContext;
import com.aryankanani.repointerviewer.contracts.analysis.SnippetRef;
import com.aryankanani.repointerviewer.contracts.analysis.SubmitProjectRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analysis side's internal API (task 4.1): {@link AnalysisApi} over {@code ProjectService}, {@code CodebaseQueries}
 * and {@code Retriever}. The paths come from the interface's {@code @HttpExchange} annotations. In one JVM the
 * callers get this bean itself as their {@code AnalysisApi}, so they already use only the contract; from task 4.1
 * step 2b they reach it over HTTP. Errors: {@link AnalysisApiErrors}.
 */
@RestController
class AnalysisApiController implements AnalysisApi {

	/** A caller cannot ask for the whole table (the GraphQL API also caps at 50). */
	private static final int MAX_PAGE_SIZE = 50;

	private final ProjectService projectService;

	private final CodebaseQueries codebaseQueries;

	private final Retriever retriever;

	AnalysisApiController(ProjectService projectService, CodebaseQueries codebaseQueries, Retriever retriever) {
		this.projectService = projectService;
		this.codebaseQueries = codebaseQueries;
		this.retriever = retriever;
	}

	@Override
	public ProjectDto submit(SubmitProjectRequest request) {
		return toDto(projectService.submit(new SubmitProjectCommand(request.githubUrl(), request.goal(),
				request.domain(), request.targetRole())));
	}

	@Override
	public ProjectDto reanalyze(UUID id) {
		return toDto(projectService.reanalyze(id));
	}

	@Override
	public List<ProjectDto> findByIds(List<UUID> ids) {
		return projectService.findByIds(ids).values().stream().map(AnalysisApiController::toDto).toList();
	}

	@Override
	public ProjectPage page(int limit, Instant afterCreatedAt, UUID afterId) {
		if ((afterCreatedAt == null) != (afterId == null)) {
			throw new DomainException(ErrorCode.INVALID_INPUT, "Give both afterCreatedAt and afterId, or neither.");
		}
		ScrollPosition position = afterId == null ? ScrollPosition.keyset()
				: ScrollPosition.forward(Map.of("createdAt", afterCreatedAt, "id", afterId));
		Window<Project> window = projectService.page(position, Math.clamp(limit, 1, MAX_PAGE_SIZE));
		return new ProjectPage(window.getContent().stream().map(AnalysisApiController::toDto).toList(),
				window.hasNext());
	}

	@Override
	public ProjectContext context(UUID id) {
		return projectService.context(id)
				.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Project not found.", "projectId"));
	}

	@Override
	public CodeNames codeNames(UUID id) {
		return new CodeNames(codebaseQueries.paths(id), codebaseQueries.symbols(id));
	}

	@Override
	public RetrievedContext searchChunks(UUID id, ChunkSearchRequest request) {
		return retriever.forTopic(id, request.query(), request.maxTokens());
	}

	@Override
	public RetrievedContext chunksForRanges(UUID id, ChunkRangesRequest request) {
		return retriever.forRanges(id, request.ranges(), request.maxTokens());
	}

	@Override
	public List<String> snippets(List<SnippetRef> refs) {
		return codebaseQueries.snippets(refs);
	}

	private static ProjectDto toDto(Project project) {
		return new ProjectDto(project.getId(), project.getGithubUrl(), project.getOwner(), project.getRepoName(),
				project.getCommitSha(), project.getStatus(), project.getFailureReason(), project.getGoal(),
				project.getDomain(), project.getTargetRole(), stats(project), project.getSummary(),
				project.getCreatedAt(), project.getUpdatedAt());
	}

	/** All counts, or null: the counts are written together, at the end of the PARSING stage. */
	private static ProjectDto.Stats stats(Project project) {
		if (project.getFileCount() == null || project.getJavaFileCount() == null || project.getTotalLines() == null
				|| project.getChunkCount() == null) {
			return null;
		}
		return new ProjectDto.Stats(project.getFileCount(), project.getJavaFileCount(), project.getTotalLines(),
				project.getChunkCount(), project.getPrimaryLanguage());
	}

}
