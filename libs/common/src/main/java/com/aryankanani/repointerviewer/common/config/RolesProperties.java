package com.aryankanani.repointerviewer.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.roles}: which jobs a service instance does (task 3.3, LLD §14.3). The flags start or keep stopped each
 * {@code @KafkaListener} ({@code autoStartup}), so a service can run its workers apart from its API. A service sets
 * only its own; one it does not have is false. The GraphQL gateway has no roles (task 4.1).
 *
 * @param api this service's internal REST API
 * @param analysisWorker analysis-service's ingest, embed and summary consumers
 * @param interviewWorker interview-service's evaluation consumer and orchestrator
 */
@Validated
@ConfigurationProperties(prefix = "app.roles", ignoreUnknownFields = false)
public record RolesProperties(boolean api, boolean analysisWorker, boolean interviewWorker) {
}
