package com.aryankanani.repointerviewer.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryankanani.repointerviewer.FakeAnalysisApi;
import com.aryankanani.repointerviewer.FakeInterviewApi;
import com.aryankanani.repointerviewer.IntegrationTest;
import com.aryankanani.repointerviewer.TestUsers;
import com.aryankanani.repointerviewer.common.error.ServiceUnavailableException;
import com.aryankanani.repointerviewer.contracts.analysis.ProjectDto;
import com.aryankanani.repointerviewer.contracts.interview.SessionDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * Task 4.4: {@code deleteProject} removes the caller's project with its interviews; the interviews go first, so a
 * delete that stops halfway never leaves interviews of a project that is gone.
 */
@IntegrationTest
class DeleteProjectTest {

	private static final String DELETE = """
			mutation ($input: DeleteProjectInput!) {
			  deleteProject(input: $input) { deletedProjectId errors { code field } }
			}
			""";

	private final GraphQlTester graphQlTester;

	private final FakeAnalysisApi analysis;

	private final FakeInterviewApi interviews;

	DeleteProjectTest(@Autowired GraphQlTester graphQlTester, @Autowired FakeAnalysisApi analysis,
			@Autowired FakeInterviewApi interviews) {
		this.graphQlTester = graphQlTester;
		this.analysis = analysis;
		this.interviews = interviews;
	}

	@BeforeEach
	void emptyFakes() {
		analysis.reset();
		interviews.reset();
	}

	@Test
	void theCallersProjectGoesWithItsInterviews() {
		UUID project = analysis.readyProject();
		SessionDto session = interviews.add(FakeInterviewApi.session(project));
		UUID kept = analysis.readyProject();
		SessionDto keptSession = interviews.add(FakeInterviewApi.session(kept));

		delete(project)
				.path("deleteProject.errors").entityList(Object.class).hasSize(0)
				.path("deleteProject.deletedProjectId").entity(UUID.class).isEqualTo(project);

		assertThat(analysis.findByIds(List.of(project, kept))).extracting(ProjectDto::id).containsExactly(kept);
		assertThat(interviews.sessions(List.of(session.id(), keptSession.id()))).extracting(SessionDto::id)
				.containsExactly(keptSession.id());
	}

	@Test
	void anotherUsersProjectIsForbiddenAndStays() {
		UUID theirs = analysis.readyProjectOf(TestUsers.OTHER);

		delete(theirs)
				.path("deleteProject.deletedProjectId").valueIsNull()
				.path("deleteProject.errors[0].code").entity(String.class).isEqualTo("FORBIDDEN")
				.path("deleteProject.errors[0].field").entityList(String.class).containsExactly("input", "projectId");
		assertThat(analysis.calls("delete")).isZero();
		assertThat(interviews.calls("deleteByProject")).isZero();
	}

	@Test
	void anUnknownProjectIsNotFound() {
		delete(UUID.randomUUID()).path("deleteProject.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND");
	}

	/** interview-service down: its delete fails, so the project is not deleted either, and the delete can be retried. */
	@Test
	void whenTheInterviewsCannotBeDeletedTheProjectStays() {
		UUID project = analysis.readyProject();
		interviews.failNextCall(new ServiceUnavailableException("The interview service is not available right now. "
				+ "Try again in a minute."));

		delete(project).path("deleteProject.errors[0].code").entity(String.class).isEqualTo("SERVICE_UNAVAILABLE");

		assertThat(analysis.calls("delete")).isZero();
		assertThat(analysis.findByIds(List.of(project))).hasSize(1);
	}

	private GraphQlTester.Response delete(UUID project) {
		return graphQlTester.document(DELETE).variable("input", Map.of("projectId", project)).execute();
	}

}
