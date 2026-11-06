/**
 * Everything about the analyzed code: fetching repos, scanning, chunking, secret redaction,
 * embeddings, the repo map and retrieval. Owns {@code code_file} and {@code code_chunk}.
 *
 * <p>May depend on: {@code common}. Only {@code codebase.embed} may use
 * {@code org.springframework.ai.embedding}. Other modules read it through queries and read models.
 * Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.codebase;
