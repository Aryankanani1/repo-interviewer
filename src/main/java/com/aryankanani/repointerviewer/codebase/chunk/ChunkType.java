package com.aryankanani.repointerviewer.codebase.chunk;

/** Mirrors the {@code ck_chunk_type} check on {@code code_chunk.chunk_type} one-to-one (matched by name). */
public enum ChunkType {
	/** A condensed outline of a Java type: imports, declaration, fields, method signatures. */
	TYPE_HEADER,
	/** One method or constructor, a run of small methods, or one window of a long method. */
	METHOD,
	/** YAML or properties. */
	CONFIG,
	/** Markdown. */
	DOC,
	SQL,
	/** Anything else: pom.xml, Dockerfile, Java that does not parse. */
	TEXT
}
