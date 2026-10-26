package com.aryankanani.repointerviewer.api.graphql;

import com.aryankanani.repointerviewer.api.error.UserErrorMapper;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.project.ProjectService;
import com.aryankanani.repointerviewer.project.SubmitProjectCommand;
import java.util.UUID;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.stereotype.Controller;

/**
 * Resolvers for {@code Query.project}, {@code Query.projects} and {@code Mutation.submitProject}
 * (schema-first: the method name is the field name). Maps entities to views; no business logic here.
 */
@Controller
class ProjectController {

	private static final int DEFAULT_PAGE_SIZE = 20;   // same as "first: Int = 20" in the schema

	private static final int MAX_PAGE_SIZE = 50;        // a client cannot ask for the whole table

	private final ProjectService projectService;

	ProjectController(ProjectService projectService) {
		this.projectService = projectService;
	}

	/** {@code project(id: ID!): Project}. Not found returns null (the field is nullable), not an error. */
	@QueryMapping
	ProjectView project(@Argument UUID id) {
		return projectService.find(id).map(ProjectView::from).orElse(null);
	}

	/**
	 * {@code projects(first, after): ProjectConnection!}. Spring builds {@code ScrollSubrange} from
	 * {@code first}/{@code after} (the cursor decodes back to a keyset position), and turns the returned
	 * {@link Window} into edges, cursors and pageInfo, because the schema type ends in "Connection".
	 */
	@QueryMapping
	Window<ProjectView> projects(ScrollSubrange subrange) {
		ScrollPosition position = subrange.position().orElse(ScrollPosition.keyset());   // no "after": first page
		int pageSize = Math.clamp(subrange.count().orElse(DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
		return projectService.page(position, pageSize).map(ProjectView::from);
	}

	/**
	 * {@code submitProject(input): SubmitProjectPayload!}. Expected failures (bad URL, missing repo) come
	 * back as {@code errors} in the payload, not as GraphQL errors, so clients can show them per field.
	 */
	@MutationMapping
	SubmitProjectPayload submitProject(@Argument SubmitProjectInput input) {
		var command = new SubmitProjectCommand(input.githubUrl(), input.goal(), input.domain(), input.targetRole());
		try {
			return SubmitProjectPayload.success(ProjectView.from(projectService.submit(command)));
		}
		catch (DomainException ex) {
			return SubmitProjectPayload.failure(UserErrorMapper.toUserError(ex));
		}
	}

}
