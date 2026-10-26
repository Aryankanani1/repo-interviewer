/**
 * Shared kernel: ids, clock, domain errors ({@code DomainException}, {@code ErrorCode}),
 * {@code TokenEstimator}, JSON helpers and {@code AppProperties}.
 *
 * <p>May depend on: nothing in this application. Every other module may depend on it.
 * Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.common;
