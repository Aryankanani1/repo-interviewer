/**
 * The GraphQL API: controllers, scalars, input and output mapping, and error mapping.
 * No business logic, and no LLM calls while resolving fields.
 *
 * <p>May depend on: {@code contracts} (the records of {@code AnalysisApi} and {@code InterviewApi}, task 4.1),
 * {@code common}.
 * Nothing depends on {@code api}. The only module allowed to use {@code org.springframework.graphql}.
 * Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.api;
