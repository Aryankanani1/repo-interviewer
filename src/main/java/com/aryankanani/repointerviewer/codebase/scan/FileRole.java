package com.aryankanani.repointerviewer.codebase.scan;

/**
 * What a file is for, picked by {@link FileClassifier}. Mirrors the GraphQL enum {@code FileRole} and the
 * {@code ck_code_file_role} check one-to-one (matched by name).
 */
public enum FileRole {
	CONTROLLER,
	SERVICE,
	REPOSITORY,
	ENTITY,
	DTO,
	CONFIG,
	SECURITY,
	MESSAGING,
	TEST,
	MIGRATION,
	BUILD,
	DOC,
	OTHER
}
