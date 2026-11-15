/**
 * Shared kernel: ids, clock, domain errors ({@code DomainException}, {@code ErrorCode}), errors between the services
 * ({@code web}), the Kafka plumbing without a database ({@code messaging}; the outbox is {@code libs/outbox} since
 * task 4.1), {@code TokenEstimator}, and the {@code app.*} settings every service shares ({@code config}).
 *
 * <p>May depend on: nothing in this application. Every other module may depend on it.
 * Rules: docs/LLD.md §2, enforced by {@code ArchitectureTest}.
 */
package com.aryankanani.repointerviewer.common;
