/**
 * The project aggregate (one analyzed repo at one commit), its status machine, and analysis
 * orchestration behind the {@code AnalysisPipeline} port.
 *
 * <p>May depend on: {@code codebase}, {@code llm}, {@code common}.
 * Other modules get records from {@code ProjectService} queries, never the {@code Project} entity
 * (only {@code api} may map entities to views).
 * Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.project;
