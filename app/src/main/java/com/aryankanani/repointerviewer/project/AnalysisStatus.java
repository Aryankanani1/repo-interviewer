package com.aryankanani.repointerviewer.project;

/**
 * Where a project is in its analysis. Mirrors the GraphQL enum {@code AnalysisStatus} one-to-one
 * (values are matched by name) and the {@code ck_project_status} check in V1__init.sql.
 * Allowed transitions: docs/LLD.md §3.2.
 */
public enum AnalysisStatus {
	SUBMITTED,
	CLONING,
	PARSING,
	EMBEDDING,
	SUMMARIZING,
	READY,
	FAILED
}
