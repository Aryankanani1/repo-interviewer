package com.aryankanani.repointerviewer.project;

import java.util.UUID;

/**
 * What other modules may read about a project (LLD §2): {@code interview} gets this record, never the
 * {@link Project} entity. Same shape as the Phase 4 endpoint {@code GET /internal/projects/{id}/context}.
 *
 * @param name {@code owner/repo}
 * @param goal the candidate's own words, untrusted
 * @param targetRole the candidate's own words, untrusted
 * @param summary null until READY
 * @param repoMap null until PARSING finished
 */
public record ProjectContext(UUID id, AnalysisStatus status, String name, String goal, String targetRole,
		RepoSummary summary, String repoMap) {

	/** Leaves out the texts: the summary and the map describe repository code. */
	@Override
	public String toString() {
		return "ProjectContext[id=" + id + ", status=" + status + ", name=" + name + "]";
	}

}
