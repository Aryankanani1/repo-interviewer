package com.aryankanani.repointerviewer.project;

import static com.aryankanani.repointerviewer.project.AnalysisStatus.CLONING;
import static com.aryankanani.repointerviewer.project.AnalysisStatus.EMBEDDING;
import static com.aryankanani.repointerviewer.project.AnalysisStatus.FAILED;
import static com.aryankanani.repointerviewer.project.AnalysisStatus.PARSING;
import static com.aryankanani.repointerviewer.project.AnalysisStatus.READY;
import static com.aryankanani.repointerviewer.project.AnalysisStatus.SUBMITTED;
import static com.aryankanani.repointerviewer.project.AnalysisStatus.SUMMARIZING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Task 1.9: the project state machine (LLD §3.2). No Spring, no database. */
class ProjectTest {

	/** The diagram in LLD §3.2, written out once more as the expected answer. */
	private static final Map<AnalysisStatus, Set<AnalysisStatus>> ARROWS = Map.of(
			SUBMITTED, Set.of(CLONING),
			CLONING, Set.of(PARSING, FAILED),
			PARSING, Set.of(EMBEDDING, FAILED),
			EMBEDDING, Set.of(SUMMARIZING, FAILED),
			SUMMARIZING, Set.of(READY, FAILED),
			READY, Set.of(),
			FAILED, Set.of(SUBMITTED));

	/** Every one of the 49 (from, to) pairs: allowed exactly when it is an arrow. */
	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("everyPair")
	void onlyTheArrowsOfTheStateMachineAreAllowed(AnalysisStatus from, AnalysisStatus to) {
		Project project = projectIn(from);

		if (ARROWS.get(from).contains(to)) {
			project.transitionTo(to);
			assertThat(project.getStatus()).isEqualTo(to);
		}
		else {
			assertThatThrownBy(() -> project.transitionTo(to)).isInstanceOf(IllegalStateTransitionException.class)
					.hasMessageContaining(from + " to " + to);
			assertThat(project.getStatus()).isEqualTo(from);
		}
	}

	@Test
	void aFullAnalysisRecordsEverything() {
		Project project = projectIn(SUBMITTED);
		RepoSummary summary = new RepoSummary("Shop", "layered", List.of(), List.of(), List.of(), List.of());

		project.startAnalysis();
		project.transitionTo(PARSING);
		project.recordIndex(11, 7, 300, 29, "java", "./");
		project.transitionTo(SUMMARIZING);
		project.complete(summary);

		assertThat(project.getStatus()).isEqualTo(READY);
		assertThat(project.getChunkCount()).isEqualTo(29);
		assertThat(project.getRepoMap()).isEqualTo("./");
		assertThat(project.getSummary()).isEqualTo(summary);
		assertThat(project.getAnalysisCompletedAt()).isAfterOrEqualTo(project.getAnalysisStartedAt());
	}

	@Test
	void reanalyzeClearsTheReasonAndTheNextStartClearsOldResults() {
		Project project = projectIn(EMBEDDING);
		project.fail("Analysis failed while indexing the code for search.");

		project.reanalyze();
		assertThat(project.getStatus()).isEqualTo(SUBMITTED);
		assertThat(project.getFailureReason()).isNull();

		project.startAnalysis();
		assertThat(project.getFileCount()).isNull();
		assertThat(project.getRepoMap()).isNull();
		assertThat(project.getAnalysisCompletedAt()).isNull();
	}

	static List<Arguments> everyPair() {
		List<Arguments> pairs = new ArrayList<>();
		for (AnalysisStatus from : AnalysisStatus.values()) {
			for (AnalysisStatus to : AnalysisStatus.values()) {
				pairs.add(Arguments.of(from, to));
			}
		}
		return pairs;
	}

	/** A project in the given status, reached only through allowed steps. */
	private static Project projectIn(AnalysisStatus status) {
		GithubUrl url = GithubUrl.parse("https://github.com/acme/shop");
		Project project = new Project(url, new RemoteHead("1111111111111111111111111111111111111111", "main"),
				new SubmitProjectCommand(url.normalized(), null, null, null));
		List<Runnable> path = switch (status) {
			case SUBMITTED -> List.of();
			case CLONING -> List.of(project::startAnalysis);
			case PARSING -> List.of(project::startAnalysis, () -> project.transitionTo(PARSING));
			case EMBEDDING -> List.of(project::startAnalysis, () -> project.transitionTo(PARSING),
					() -> project.recordIndex(1, 1, 1, 1, "java", "./"));
			case SUMMARIZING -> List.of(project::startAnalysis, () -> project.transitionTo(PARSING),
					() -> project.recordIndex(1, 1, 1, 1, "java", "./"), () -> project.transitionTo(SUMMARIZING));
			case READY -> List.of(project::startAnalysis, () -> project.transitionTo(PARSING),
					() -> project.recordIndex(1, 1, 1, 1, "java", "./"), () -> project.transitionTo(SUMMARIZING),
					() -> project.complete(null));
			case FAILED -> List.of(project::startAnalysis, () -> project.fail("x"));
		};
		path.forEach(Runnable::run);
		assertThat(project.getStatus()).isEqualTo(status);
		return project;
	}

}
