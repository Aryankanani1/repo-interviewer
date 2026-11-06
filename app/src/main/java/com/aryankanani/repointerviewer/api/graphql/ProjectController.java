package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.InputValidator;
import com.aryankanani.repointerviewer.api.error.UserErrorMapper;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.contracts.analysis.AnalysisApi;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectPage;
import com.aryankanani.repointerviewer.contracts.analysis.SubmitProjectRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.stereotype.Controller;

/**
 * Resolvers for {@code Query.project}, {@code Query.projects}, {@code Mutation.submitProject} and
 * {@code Mutation.reanalyzeProject}
 * (schema-first: the method name is the field name). Calls the analysis side's {@link AnalysisApi} and maps its
 * records to views; no business logic here.
 */
@Controller
class ProjectController {

	private static final int DEFAULT_PAGE_SIZE = 20;   // same as "first: Int = 20" in the schema

	private static final int MAX_PAGE_SIZE = 50;        // a client cannot ask for the whole table

	/** The keys of a page cursor: the last project's {@code createdAt} and {@code id}, as strings. */
	private static final String CREATED_AT = "createdAt";

	private static final String ID = "id";

	private final AnalysisApi analysis;

	private final InputValidator inputValidator;

	ProjectController(AnalysisApi analysis, InputValidator inputValidator) {
		this.analysis = analysis;
		this.inputValidator = inputValidator;
	}

	/** {@code project(id: ID!): Project}. Not found returns null (the field is nullable), not an error. */
	@QueryMapping
	ProjectView project(@Argument UUID id) {
		return analysis.findByIds(List.of(id)).stream().findFirst().map(ProjectView::from).orElse(null);
	}

	/**
	 * {@code projects(first, after): ProjectConnection!}. Spring builds {@code ScrollSubrange} from
	 * {@code first}/{@code after} (the cursor decodes back to the keyset position written by {@link #after}), and
	 * turns the returned {@link Window} into edges, cursors and pageInfo, because the schema type ends in
	 * "Connection".
	 */
	@QueryMapping
	Window<ProjectView> projects(ScrollSubrange subrange) {
		Map<String, ?> cursor = subrange.position()   // no "after": the first page
				.map(position -> ((KeysetScrollPosition) position).getKeys()).orElse(Map.of());
		int pageSize = Math.clamp(subrange.count().orElse(DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
		ProjectPage page = analysis.page(pageSize,
				cursor.isEmpty() ? null : Instant.parse((String) cursor.get(CREATED_AT)),
				cursor.isEmpty() ? null : UUID.fromString((String) cursor.get(ID)));
		List<ProjectDto> projects = page.items();
		return Window.from(projects.stream().map(ProjectView::from).toList(), i -> after(projects.get(i)),
				page.hasNext());
	}

	/** The position just after {@code project}, its keys as strings, so its cursor decodes back to the same strings. */
	private static ScrollPosition after(ProjectDto project) {
		return ScrollPosition.forward(Map.of(CREATED_AT, project.createdAt().toString(), ID, project.id().toString()));
	}

	/**
	 * {@code submitProject(input): SubmitProjectPayload!}. Expected failures (bad URL, missing repo) come
	 * back as {@code errors} in the payload, not as GraphQL errors, so clients can show them per field.
	 */
	@MutationMapping
	SubmitProjectPayload submitProject(@Argument SubmitProjectInput input) {
		var invalid = inputValidator.firstError(input);
		if (invalid.isPresent()) {
			return SubmitProjectPayload.failure(invalid.get());
		}
		var request = new SubmitProjectRequest(input.githubUrl(), input.goal(), input.domain(), input.targetRole());
		try {
			return SubmitProjectPayload.success(ProjectView.from(analysis.submit(request)));
		}
		catch (DomainException ex) {
			return SubmitProjectPayload.failure(UserErrorMapper.toUserError(ex));
		}
	}

	/** {@code reanalyzeProject(input): SubmitProjectPayload!}. Retries a FAILED analysis. */
	@MutationMapping
	SubmitProjectPayload reanalyzeProject(@Argument ReanalyzeProjectInput input) {
		try {
			return SubmitProjectPayload.success(ProjectView.from(analysis.reanalyze(input.projectId())));
		}
		catch (DomainException ex) {
			return SubmitProjectPayload.failure(UserErrorMapper.toUserError(ex));
		}
	}

}
